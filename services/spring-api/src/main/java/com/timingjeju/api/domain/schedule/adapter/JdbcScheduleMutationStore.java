package com.timingjeju.api.domain.schedule.adapter;

import com.timingjeju.api.application.schedule.CreateScheduleItemCommand;
import com.timingjeju.api.application.schedule.DeleteScheduleItemCommand;
import com.timingjeju.api.application.schedule.MoveScheduleItemCommand;
import com.timingjeju.api.application.schedule.PatchScheduleItemCommand;
import com.timingjeju.api.application.schedule.ReorderScheduleCommand;
import com.timingjeju.api.application.schedule.ScheduleEditRecord;
import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.schedule.ScheduleMutationRecord;
import com.timingjeju.api.application.schedule.ScheduleMutationResult;
import com.timingjeju.api.application.schedule.ScheduleMutationStore;
import com.timingjeju.api.application.trip.TripAggregateMutationCoordinator;
import com.timingjeju.api.application.trip.TripAggregateMutationPlan;
import com.timingjeju.api.application.trip.TripAggregateMutationState;
import com.timingjeju.api.application.trip.TripRootPatch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcScheduleMutationStore implements ScheduleMutationStore {
  private static final ZoneId JEJU = ZoneId.of("Asia/Seoul");
  private final JdbcTemplate jdbc;
  private final TripAggregateMutationCoordinator coordinator;

  public JdbcScheduleMutationStore(
      JdbcTemplate jdbc, TripAggregateMutationCoordinator coordinator) {
    this.jdbc = jdbc;
    this.coordinator = coordinator;
  }

  @Override
  @Transactional
  public ScheduleMutationResult addItem(ScheduleMutationRecord record) {
    if (!record.expectedTrip().tripId().equals(record.tripId())) {
      throw ScheduleException.tripVersionConflict();
    }
    return coordinator
        .executeMonotonic(
            record.ownerId(),
            record.tripId(),
            record.expectedTrip().revision(),
            record.transactionTime(),
            (state, committedAt) -> createMutationPlan(record, state, committedAt))
        .payload();
  }

  private TripAggregateMutationPlan<ScheduleMutationResult> createMutationPlan(
      ScheduleMutationRecord record, TripAggregateMutationState state, Instant committedAt) {
    try {
      UUID activeVersionId = state.activeScheduleVersionId();
      if (activeVersionId == null) {
        throw ScheduleException.versionNotFound();
      }
      if (!record.command().expectedActiveScheduleVersionId().equals(activeVersionId)) {
        throw ScheduleException.activeVersionConflict();
      }
      Day day = loadDay(record.tripId(), record.command().dayNo());
      validateTimeAndPosition(record.command(), day, activeVersionId);
      ResolvedReference reference = resolveReference(record.tripId(), record.command());

      int versionNo = nextVersionNo(record.tripId());
      UUID newVersionId = UUID.randomUUID();
      jdbc.update(
          """
          insert into public.trip_schedule_versions
            (id, trip_plan_id, version_no, base_schedule_version_id, status, source_type,
             summary, resulting_score, created_by_user_id, created_at)
          values (?, ?, ?, ?, 'draft', 'user_edit', ?, null, ?, ?)
          """,
          newVersionId,
          record.tripId(),
          versionNo,
          activeVersionId,
          "사용자 일정 항목 추가",
          record.ownerId(),
          Timestamp.from(committedAt));

      List<SourceItem> sourceItems = loadSourceItems(record.tripId(), activeVersionId);
      List<NewItem> newItems = new ArrayList<>(sourceItems.size() + 1);
      Map<UUID, UUID> copiedIds = new HashMap<>();
      for (SourceItem source : sourceItems) {
        UUID newId = UUID.randomUUID();
        int sequence =
            source.dayId().equals(day.id()) && source.sequenceNo() >= record.command().sequenceNo()
                ? source.sequenceNo() + 1
                : source.sequenceNo();
        insertCopiedItem(record.tripId(), newVersionId, newId, sequence, source);
        newItems.add(source.asNew(newId, sequence, source));
        copiedIds.put(source.id(), newId);
      }
      copyProgress(record.tripId(), activeVersionId, newVersionId, copiedIds, committedAt);

      UUID changedItemId = UUID.randomUUID();
      Instant plannedStart = record.command().plannedStartAt().toInstant();
      Instant plannedEnd = plannedStart.plusSeconds(record.command().stayMinutes() * 60L);
      insertAddedItem(
          record, day, reference, newVersionId, changedItemId, plannedStart, plannedEnd);
      newItems.add(
          new NewItem(
              changedItemId,
              null,
              day.id(),
              record.command().sequenceNo(),
              record.command().itemType(),
              reference.placeId(),
              plannedStart,
              plannedEnd,
              false));

      assertRequiredReferences(record.tripId(), newVersionId);
      Map<ItemPair, SourceLeg> sourceLegs = loadSourceLegs(record.tripId(), activeVersionId);
      copyOrDeriveLegs(
          record.tripId(),
          newVersionId,
          newItems,
          sourceLegs,
          preferredTransportMode(record.tripId()),
          committedAt);

      jdbc.queryForObject(
          "select public.assert_schedule_version_sealable(?, ?)",
          (rs, row) -> 1,
          newVersionId,
          record.tripId());
      jdbc.queryForObject(
          "select public.assert_schedule_item_required_references(?, ?)",
          (rs, row) -> 1,
          newVersionId,
          record.tripId());
      jdbc.update(
          "update public.trip_schedule_versions set status='superseded' where id=? and trip_plan_id=? and status='active'",
          activeVersionId,
          record.tripId());
      if (jdbc.update(
              "update public.trip_schedule_versions set status='active', applied_at=? where id=? and trip_plan_id=? and status='draft'",
              Timestamp.from(committedAt),
              newVersionId,
              record.tripId())
          != 1) {
        throw ScheduleException.activeVersionConflict();
      }
      ScheduleMutationResult result =
          new ScheduleMutationResult(
              record.tripId(),
              activeVersionId,
              newVersionId,
              versionNo,
              state.revision() + 1,
              List.of(changedItemId),
              committedAt);
      return TripAggregateMutationPlan.maintain(
          TripRootPatch.unchanged(),
          () -> updateActiveSchedulePointer(record, activeVersionId, newVersionId),
          result);
    } catch (DataIntegrityViolationException failure) {
      throw ScheduleException.itemInvalid();
    } catch (DataAccessException failure) {
      throw ScheduleException.internalServerError();
    }
  }

  private void updateActiveSchedulePointer(
      ScheduleMutationRecord record, UUID previousVersionId, UUID newVersionId) {
    updateActiveSchedulePointer(record.ownerId(), record.tripId(), previousVersionId, newVersionId);
  }

  private void updateActiveSchedulePointer(
      UUID ownerId, UUID tripId, UUID previousVersionId, UUID newVersionId) {
    try {
      if (jdbc.update(
              """
              update public.trip_plans
              set active_schedule_version_id=?, stale=true
              where id=? and user_id=? and active_schedule_version_id=?
              """,
              newVersionId,
              tripId,
              ownerId,
              previousVersionId)
          != 1) {
        throw ScheduleException.activeVersionConflict();
      }
    } catch (DataIntegrityViolationException failure) {
      throw ScheduleException.itemInvalid();
    } catch (DataAccessException failure) {
      throw ScheduleException.internalServerError();
    }
  }

  @Override
  @Transactional
  public ScheduleMutationResult patchItem(ScheduleEditRecord<PatchScheduleItemCommand> record) {
    return edit(record, "사용자 일정 항목 수정", items -> patch(items, record), List.of(record.itemId()));
  }

  @Override
  @Transactional
  public ScheduleMutationResult deleteItem(ScheduleEditRecord<DeleteScheduleItemCommand> record) {
    return edit(record, "사용자 일정 항목 삭제", items -> delete(items, record), List.of());
  }

  @Override
  @Transactional
  public ScheduleMutationResult reorder(ScheduleEditRecord<ReorderScheduleCommand> record) {
    List<UUID> changed =
        record.command().days().stream().flatMap(day -> day.orderedItemIds().stream()).toList();
    return edit(record, "사용자 일정 순서 변경", items -> reorder(items, record), changed);
  }

  @Override
  @Transactional
  public ScheduleMutationResult moveItem(ScheduleEditRecord<MoveScheduleItemCommand> record) {
    return edit(record, "사용자 일정 항목 Day 이동", items -> move(items, record), List.of(record.itemId()));
  }

  private <T> ScheduleMutationResult edit(
      ScheduleEditRecord<T> record, String summary, ItemEditor<T> editor, List<UUID> changedIds) {
    if (!record.expectedTrip().tripId().equals(record.tripId())) {
      throw ScheduleException.tripVersionConflict();
    }
    return coordinator
        .executeMonotonic(
            record.ownerId(),
            record.tripId(),
            record.expectedTrip().revision(),
            record.transactionTime(),
            (state, committedAt) ->
                createEditMutationPlan(record, state, committedAt, summary, editor, changedIds))
        .payload();
  }

  private <T> TripAggregateMutationPlan<ScheduleMutationResult> createEditMutationPlan(
      ScheduleEditRecord<T> record,
      TripAggregateMutationState state,
      Instant committedAt,
      String summary,
      ItemEditor<T> editor,
      List<UUID> changedIds) {
    try {
      UUID activeVersionId = requireExpectedActiveVersion(expectedVersion(record.command()), state);
      List<SourceItem> sourceItems = loadSourceItems(record.tripId(), activeVersionId);
      Map<UUID, SourceItem> originalById =
          sourceItems.stream()
              .collect(java.util.stream.Collectors.toMap(SourceItem::id, item -> item));
      List<SourceItem> editedItems = editor.apply(new ArrayList<>(sourceItems));
      if (editedItems.isEmpty()) throw ScheduleException.dayEmpty();

      int versionNo = nextVersionNo(record.tripId());
      UUID newVersionId = UUID.randomUUID();
      insertDraft(record, activeVersionId, newVersionId, versionNo, summary, committedAt);
      List<NewItem> newItems = new ArrayList<>(editedItems.size());
      Map<UUID, UUID> copiedIds = new HashMap<>();
      for (SourceItem source : editedItems) {
        UUID newId = UUID.randomUUID();
        insertCopiedItem(record.tripId(), newVersionId, newId, source.sequenceNo(), source);
        newItems.add(source.asNew(newId, source.sequenceNo(), originalById.get(source.id())));
        copiedIds.put(source.id(), newId);
      }
      copyProgress(record.tripId(), activeVersionId, newVersionId, copiedIds, committedAt);
      assertRequiredReferences(record.tripId(), newVersionId);
      copyOrDeriveLegs(
          record.tripId(),
          newVersionId,
          newItems,
          loadSourceLegs(record.tripId(), activeVersionId),
          preferredTransportMode(record.tripId()),
          committedAt);
      sealAndActivate(record.tripId(), activeVersionId, newVersionId, committedAt);
      List<UUID> changedItemIds = changedIds.stream().map(copiedIds::get).distinct().toList();
      if (changedItemIds.stream().anyMatch(java.util.Objects::isNull)) {
        throw ScheduleException.itemNotFound();
      }
      ScheduleMutationResult result =
          new ScheduleMutationResult(
              record.tripId(),
              activeVersionId,
              newVersionId,
              versionNo,
              state.revision() + 1,
              changedItemIds,
              committedAt);
      return TripAggregateMutationPlan.maintain(
          TripRootPatch.unchanged(),
          () ->
              updateActiveSchedulePointer(
                  record.ownerId(), record.tripId(), activeVersionId, newVersionId),
          result);
    } catch (DataIntegrityViolationException failure) {
      throw ScheduleException.itemInvalid();
    } catch (DataAccessException failure) {
      throw ScheduleException.internalServerError();
    }
  }

  private List<SourceItem> patch(
      List<SourceItem> items, ScheduleEditRecord<PatchScheduleItemCommand> record) {
    int index = targetIndex(items, record.itemId());
    ensureNotCompleted(record.tripId(), expectedVersion(record.command()), record.itemId());
    SourceItem source = items.get(index);
    PatchScheduleItemCommand patch = record.command();
    UUID placeId = patch.changes("placeId") ? patch.placeId() : source.placeId();
    UUID accommodationId =
        patch.changes("accommodationId") ? patch.accommodationId() : source.accommodationId();
    UUID transportEventId =
        patch.changes("transportEventId") ? patch.transportEventId() : source.transportEventId();
    String title = patch.changes("title") ? patch.title() : source.title();
    Instant start =
        patch.changes("plannedStartAt") ? patch.plannedStartAt().toInstant() : source.start();
    int stay = patch.changes("stayMinutes") ? patch.stayMinutes() : source.stayMinutes();
    var validation =
        new CreateScheduleItemCommand(
            expectedVersion(patch),
            1,
            source.sequenceNo(),
            source.itemType(),
            placeId,
            accommodationId,
            transportEventId,
            title,
            start.atZone(JEJU).toOffsetDateTime(),
            stay,
            patch.changes("bufferAfterMinutes")
                ? patch.bufferAfterMinutes()
                : source.bufferAfterMinutes(),
            patch.changes("required") ? patch.required() : source.required(),
            patch.changes("memo") ? patch.memo() : source.memo());
    Day day = loadDayById(record.tripId(), source.dayId(), false);
    validateTime(validation.plannedStartAt(), validation.stayMinutes(), day);
    ResolvedReference reference = resolveReference(record.tripId(), validation);
    SourceItem replacement =
        new SourceItem(
            source.id(),
            source.dayId(),
            source.sequenceNo(),
            source.itemType(),
            reference.placeId(),
            reference.accommodationId(),
            reference.transportEventId(),
            patch.changes("title")
                ? title
                : (referencesChanged(patch) ? reference.title() : source.title()),
            start,
            start.plusSeconds(stay * 60L),
            stay,
            validation.bufferAfterMinutes(),
            validation.required(),
            source.source(),
            validation.memo(),
            source.facts());
    ensureNoOverlap(items, index, replacement);
    items.set(index, replacement);
    return items;
  }

  private List<SourceItem> delete(
      List<SourceItem> items, ScheduleEditRecord<DeleteScheduleItemCommand> record) {
    int index = targetIndex(items, record.itemId());
    ensureNotCompleted(record.tripId(), expectedVersion(record.command()), record.itemId());
    UUID dayId = items.get(index).dayId();
    if (items.stream().filter(item -> item.dayId().equals(dayId)).count() <= 1) {
      throw ScheduleException.dayEmpty();
    }
    items.remove(index);
    compact(items, dayId);
    return items;
  }

  private List<SourceItem> reorder(
      List<SourceItem> items, ScheduleEditRecord<ReorderScheduleCommand> record) {
    Set<UUID> activeIds =
        items.stream().map(SourceItem::id).collect(java.util.stream.Collectors.toSet());
    List<UUID> submitted =
        record.command().days().stream().flatMap(day -> day.orderedItemIds().stream()).toList();
    if (submitted.size() != activeIds.size() || !new HashSet<>(submitted).equals(activeIds)) {
      throw ScheduleException.orderNotPermutation();
    }
    ensureNoneCompleted(record.tripId(), expectedVersion(record.command()), submitted);
    Map<UUID, SourceItem> byId =
        items.stream().collect(java.util.stream.Collectors.toMap(SourceItem::id, item -> item));
    Map<UUID, List<Instant>> slotsByDay = new HashMap<>();
    items.stream()
        .sorted(
            java.util.Comparator.comparing(SourceItem::dayId)
                .thenComparingInt(SourceItem::sequenceNo))
        .forEach(
            item ->
                slotsByDay
                    .computeIfAbsent(item.dayId(), ignored -> new ArrayList<>())
                    .add(item.start()));
    List<SourceItem> reordered = new ArrayList<>(items.size());
    for (var dayOrder : record.command().days()) {
      Day day = loadDay(record.tripId(), dayOrder.dayNo(), true);
      int sequence = 1;
      for (UUID id : dayOrder.orderedItemIds()) {
        SourceItem source = byId.get(id);
        if (source == null || !source.dayId().equals(day.id())) {
          throw ScheduleException.orderNotPermutation();
        }
        List<Instant> slots = slotsByDay.get(day.id());
        if (slots == null || sequence > slots.size()) {
          throw ScheduleException.orderNotPermutation();
        }
        Instant slotStart = slots.get(sequence - 1);
        validateTime(slotStart.atZone(JEJU).toOffsetDateTime(), source.stayMinutes(), day);
        SourceItem replacement = source.withPosition(day.id(), sequence++, slotStart);
        ensureNoOverlap(reordered, -1, replacement);
        reordered.add(replacement);
      }
    }
    if (reordered.size() != items.size()) throw ScheduleException.orderNotPermutation();
    return reordered;
  }

  private List<SourceItem> move(
      List<SourceItem> items, ScheduleEditRecord<MoveScheduleItemCommand> record) {
    int index = targetIndex(items, record.itemId());
    ensureNotCompleted(record.tripId(), expectedVersion(record.command()), record.itemId());
    SourceItem source = items.get(index);
    Day target = loadDay(record.tripId(), record.command().targetDayNo(), true);
    if (!source.dayId().equals(target.id())
        && items.stream().filter(item -> item.dayId().equals(source.dayId())).count() <= 1) {
      throw ScheduleException.dayEmpty();
    }
    items.remove(index);
    int targetCount = (int) items.stream().filter(item -> item.dayId().equals(target.id())).count();
    if (record.command().targetSequenceNo() > targetCount + 1)
      throw ScheduleException.itemInvalid();
    validateTime(record.command().plannedStartAt(), source.stayMinutes(), target);
    compact(items, source.dayId());
    for (int i = 0; i < items.size(); i++) {
      SourceItem current = items.get(i);
      if (current.dayId().equals(target.id())
          && current.sequenceNo() >= record.command().targetSequenceNo()) {
        items.set(
            i, current.withPosition(current.dayId(), current.sequenceNo() + 1, current.start()));
      }
    }
    SourceItem moved =
        source.withPosition(
            target.id(),
            record.command().targetSequenceNo(),
            record.command().plannedStartAt().toInstant());
    ensureNoOverlap(items, -1, moved);
    items.add(moved);
    return items;
  }

  private static UUID expectedVersion(Object command) {
    if (command instanceof PatchScheduleItemCommand value)
      return value.expectedActiveScheduleVersionId();
    if (command instanceof DeleteScheduleItemCommand value)
      return value.expectedActiveScheduleVersionId();
    if (command instanceof ReorderScheduleCommand value)
      return value.expectedActiveScheduleVersionId();
    if (command instanceof MoveScheduleItemCommand value)
      return value.expectedActiveScheduleVersionId();
    throw new IllegalArgumentException("unsupported schedule mutation command");
  }

  private static UUID requireExpectedActiveVersion(
      UUID expectedVersionId, TripAggregateMutationState state) {
    if (state.activeScheduleVersionId() == null) {
      throw ScheduleException.versionNotFound();
    }
    if (!expectedVersionId.equals(state.activeScheduleVersionId())) {
      throw ScheduleException.activeVersionConflict();
    }
    return state.activeScheduleVersionId();
  }

  private void insertDraft(
      ScheduleEditRecord<?> record,
      UUID activeVersionId,
      UUID versionId,
      int versionNo,
      String summary,
      Instant committedAt) {
    jdbc.update(
        """
        insert into public.trip_schedule_versions
          (id, trip_plan_id, version_no, base_schedule_version_id, status, source_type,
           summary, resulting_score, created_by_user_id, created_at)
        values (?, ?, ?, ?, 'draft', 'user_edit', ?, null, ?, ?)
        """,
        versionId,
        record.tripId(),
        versionNo,
        activeVersionId,
        summary,
        record.ownerId(),
        Timestamp.from(committedAt));
  }

  private void assertRequiredReferences(UUID tripId, UUID versionId) {
    jdbc.queryForObject(
        "select public.assert_schedule_item_required_references(?, ?)",
        (rs, row) -> 1,
        versionId,
        tripId);
  }

  private void sealAndActivate(
      UUID tripId, UUID activeVersionId, UUID newVersionId, Instant transactionTime) {
    jdbc.queryForObject(
        "select public.assert_schedule_version_sealable(?, ?)",
        (rs, row) -> 1,
        newVersionId,
        tripId);
    jdbc.queryForObject(
        "select public.assert_schedule_item_required_references(?, ?)",
        (rs, row) -> 1,
        newVersionId,
        tripId);
    jdbc.update(
        "update public.trip_schedule_versions set status='superseded' where id=? and trip_plan_id=? and status='active'",
        activeVersionId,
        tripId);
    if (jdbc.update(
            "update public.trip_schedule_versions set status='active', applied_at=? where id=? and trip_plan_id=? and status='draft'",
            Timestamp.from(transactionTime),
            newVersionId,
            tripId)
        != 1) {
      throw ScheduleException.activeVersionConflict();
    }
  }

  private int targetIndex(List<SourceItem> items, UUID itemId) {
    if (itemId == null) throw ScheduleException.itemNotFound();
    for (int index = 0; index < items.size(); index++) {
      if (items.get(index).id().equals(itemId)) return index;
    }
    throw ScheduleException.itemNotFound();
  }

  private void ensureNotCompleted(UUID tripId, UUID versionId, UUID itemId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from public.trip_item_progress where trip_plan_id=? and schedule_version_id=? and trip_item_id=? and status='completed'",
            Integer.class,
            tripId,
            versionId,
            itemId);
    if (count != null && count > 0) throw ScheduleException.itemCompleted();
  }

  private void ensureNoneCompleted(UUID tripId, UUID versionId, List<UUID> itemIds) {
    for (UUID itemId : itemIds) ensureNotCompleted(tripId, versionId, itemId);
  }

  private void copyProgress(
      UUID tripId,
      UUID oldVersionId,
      UUID newVersionId,
      Map<UUID, UUID> copiedIds,
      Instant transactionTime) {
    for (Map.Entry<UUID, UUID> entry : copiedIds.entrySet()) {
      jdbc.update(
          """
          insert into public.trip_item_progress
            (trip_plan_id, schedule_version_id, trip_item_id, status, actual_started_at,
             actual_arrived_at, actual_completed_at, updated_at)
          select trip_plan_id, ?, ?, status, actual_started_at, actual_arrived_at,
                 actual_completed_at, ?
          from public.trip_item_progress
          where trip_plan_id=? and schedule_version_id=? and trip_item_id=?
          """,
          newVersionId,
          entry.getValue(),
          Timestamp.from(transactionTime),
          tripId,
          oldVersionId,
          entry.getKey());
    }
  }

  private static boolean referencesChanged(PatchScheduleItemCommand patch) {
    return patch.changes("placeId")
        || patch.changes("accommodationId")
        || patch.changes("transportEventId");
  }

  private static void compact(List<SourceItem> items, UUID dayId) {
    List<SourceItem> dayItems =
        items.stream()
            .filter(item -> item.dayId().equals(dayId))
            .sorted(java.util.Comparator.comparingInt(SourceItem::sequenceNo))
            .toList();
    for (int sequence = 1; sequence <= dayItems.size(); sequence++) {
      SourceItem target = dayItems.get(sequence - 1);
      int index = items.indexOf(target);
      items.set(index, target.withPosition(dayId, sequence, target.start()));
    }
  }

  private static void ensureNoOverlap(
      List<SourceItem> items, int excludedIndex, SourceItem candidate) {
    for (int index = 0; index < items.size(); index++) {
      SourceItem other = items.get(index);
      if (index != excludedIndex
          && other.dayId().equals(candidate.dayId())
          && candidate.start().isBefore(other.end())
          && other.start().isBefore(candidate.end())) {
        throw ScheduleException.itemInvalid();
      }
    }
  }

  private static void validateTime(java.time.OffsetDateTime start, int stayMinutes, Day day) {
    var jejuStart = start.atZoneSameInstant(JEJU);
    var end = jejuStart.plusMinutes(stayMinutes);
    if (!jejuStart.toLocalDate().equals(day.date())
        || !end.toLocalDate().equals(day.date())
        || (day.startTime() != null && jejuStart.toLocalTime().isBefore(day.startTime()))
        || (day.endTime() != null && end.toLocalTime().isAfter(day.endTime()))) {
      throw ScheduleException.itemInvalid();
    }
  }

  private Day loadDay(UUID tripId, int dayNo) {
    return loadDay(tripId, dayNo, false);
  }

  private Day loadDay(UUID tripId, int dayNo, boolean notFoundProblem) {
    List<Day> rows =
        jdbc.query(
            "select id, trip_date, start_time, end_time from public.trip_days where trip_plan_id=? and day_no=?",
            (rs, row) ->
                new Day(
                    rs.getObject("id", UUID.class),
                    rs.getObject("trip_date", LocalDate.class),
                    nullableLocalTime(rs, "start_time"),
                    nullableLocalTime(rs, "end_time")),
            tripId,
            dayNo);
    if (rows.isEmpty()) {
      if (notFoundProblem) throw ScheduleException.tripDayNotFound();
      throw ScheduleException.itemInvalid();
    }
    return rows.getFirst();
  }

  private Day loadDayById(UUID tripId, UUID dayId, boolean notFoundProblem) {
    List<Day> rows =
        jdbc.query(
            "select id, trip_date, start_time, end_time from public.trip_days where trip_plan_id=? and id=?",
            (rs, row) ->
                new Day(
                    rs.getObject("id", UUID.class),
                    rs.getObject("trip_date", LocalDate.class),
                    nullableLocalTime(rs, "start_time"),
                    nullableLocalTime(rs, "end_time")),
            tripId,
            dayId);
    if (rows.isEmpty()) {
      if (notFoundProblem) throw ScheduleException.tripDayNotFound();
      throw ScheduleException.itemInvalid();
    }
    return rows.getFirst();
  }

  private void validateTimeAndPosition(
      CreateScheduleItemCommand command, Day day, UUID activeVersionId) {
    var start = command.plannedStartAt().atZoneSameInstant(JEJU);
    var end = start.plusMinutes(command.stayMinutes());
    if (!start.toLocalDate().equals(day.date())
        || !end.toLocalDate().equals(day.date())
        || (day.startTime() != null && start.toLocalTime().isBefore(day.startTime()))
        || (day.endTime() != null && end.toLocalTime().isAfter(day.endTime()))) {
      throw ScheduleException.itemInvalid();
    }
    Integer count =
        jdbc.queryForObject(
            "select count(*) from public.trip_items where schedule_version_id=? and trip_day_id=?",
            Integer.class,
            activeVersionId,
            day.id());
    if (count == null || command.sequenceNo() > count + 1) {
      throw ScheduleException.itemInvalid();
    }
    Integer overlaps =
        jdbc.queryForObject(
            """
            select count(*) from public.trip_items
            where schedule_version_id=? and trip_day_id=?
              and tstzrange(planned_start_at, planned_end_at, '[)') && tstzrange(?::timestamptz, ?::timestamptz, '[)')
            """,
            Integer.class,
            activeVersionId,
            day.id(),
            Timestamp.from(command.plannedStartAt().toInstant()),
            Timestamp.from(
                command.plannedStartAt().toInstant().plusSeconds(command.stayMinutes() * 60L)));
    if (overlaps != null && overlaps > 0) {
      throw ScheduleException.itemInvalid();
    }
  }

  private ResolvedReference resolveReference(UUID tripId, CreateScheduleItemCommand command) {
    ResolvedReference resolved =
        switch (command.itemType()) {
          case "place_visit" -> resolvePlace(command.placeId());
          case "accommodation" -> resolveAccommodation(tripId, command.accommodationId());
          case "arrival", "departure" ->
              resolveTransportEvent(tripId, command.transportEventId(), command.itemType());
          case "meal", "free_time", "custom" ->
              command.placeId() == null
                  ? new ResolvedReference(null, command.title(), null, null)
                  : resolvePlace(command.placeId()).withTitle(command.title());
          default -> throw ScheduleException.itemInvalid();
        };
    if (resolved.placeId() != null
        && !"place_visit".equals(command.itemType())
        && !List.of("meal", "free_time", "custom").contains(command.itemType())) {
      ensureActivePlace(resolved.placeId());
    }
    return resolved;
  }

  private void ensureActivePlace(UUID placeId) {
    Integer count =
        jdbc.queryForObject(
            """
            select count(*) from public.tour_places
            where id=? and stale=false and source_deleted_at is null and tombstoned_at is null
              and (stale_at is null or stale_at > now())
            """,
            Integer.class,
            placeId);
    if (count == null || count != 1) {
      throw ScheduleException.placeNotFound();
    }
  }

  private ResolvedReference resolvePlace(UUID placeId) {
    List<ResolvedReference> rows =
        jdbc.query(
            """
            select id, name from public.tour_places
            where id=? and stale=false and source_deleted_at is null and tombstoned_at is null
              and (stale_at is null or stale_at > now())
            """,
            (rs, row) -> new ResolvedReference(placeId, rs.getString("name"), null, null),
            placeId);
    if (rows.isEmpty()) {
      throw ScheduleException.placeNotFound();
    }
    return rows.getFirst();
  }

  private ResolvedReference resolveAccommodation(UUID tripId, UUID accommodationId) {
    List<ResolvedReference> rows =
        jdbc.query(
            """
            select a.place_id, coalesce(a.custom_name, p.name) as title
            from public.trip_accommodations a
            left join public.tour_places p on p.id=a.place_id
            where a.id=? and a.trip_plan_id=?
            """,
            (rs, row) ->
                new ResolvedReference(
                    rs.getObject("place_id", UUID.class),
                    rs.getString("title"),
                    accommodationId,
                    null),
            accommodationId,
            tripId);
    if (rows.isEmpty()) {
      throw ScheduleException.accommodationNotFound();
    }
    return rows.getFirst();
  }

  private ResolvedReference resolveTransportEvent(UUID tripId, UUID eventId, String itemType) {
    List<ResolvedReference> rows =
        jdbc.query(
            """
            select e.terminal_place_id, coalesce(e.terminal_name, p.name) as title
            from public.trip_transport_events e
            left join public.tour_places p on p.id=e.terminal_place_id
            where e.id=? and e.trip_plan_id=? and e.event_type=?
            """,
            (rs, row) ->
                new ResolvedReference(
                    rs.getObject("terminal_place_id", UUID.class),
                    rs.getString("title"),
                    null,
                    eventId),
            eventId,
            tripId,
            itemType);
    if (rows.isEmpty()) {
      throw ScheduleException.transportEventNotFound();
    }
    return rows.getFirst();
  }

  private int nextVersionNo(UUID tripId) {
    Integer next =
        jdbc.queryForObject(
            "select coalesce(max(version_no),0)+1 from public.trip_schedule_versions where trip_plan_id=?",
            Integer.class,
            tripId);
    return next == null ? 1 : next;
  }

  private List<SourceItem> loadSourceItems(UUID tripId, UUID versionId) {
    return jdbc.query(
        """
        select id, trip_day_id, sequence_no, item_type, place_id, accommodation_id,
               transport_event_id, title, planned_start_at, planned_end_at, stay_minutes,
               buffer_after_minutes, required, source, memo, facts::text
        from public.trip_items where trip_plan_id=? and schedule_version_id=?
        order by trip_day_id, sequence_no
        """,
        (rs, row) -> sourceItem(rs),
        tripId,
        versionId);
  }

  private void insertCopiedItem(
      UUID tripId, UUID versionId, UUID newId, int sequence, SourceItem source) {
    jdbc.update(
        """
        insert into public.trip_items
          (id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no, item_type,
           place_id, accommodation_id, transport_event_id, title, planned_start_at, planned_end_at,
           stay_minutes, buffer_after_minutes, required, source, memo, facts)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """,
        newId,
        tripId,
        source.dayId(),
        versionId,
        sequence,
        source.itemType(),
        source.placeId(),
        source.accommodationId(),
        source.transportEventId(),
        source.title(),
        Timestamp.from(source.start()),
        Timestamp.from(source.end()),
        source.stayMinutes(),
        source.bufferAfterMinutes(),
        source.required(),
        source.source(),
        source.memo(),
        source.facts());
  }

  private void insertAddedItem(
      ScheduleMutationRecord record,
      Day day,
      ResolvedReference reference,
      UUID versionId,
      UUID itemId,
      Instant start,
      Instant end) {
    jdbc.update(
        """
        insert into public.trip_items
          (id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no, item_type,
           place_id, accommodation_id, transport_event_id, title, planned_start_at, planned_end_at,
           stay_minutes, buffer_after_minutes, required, source, memo, facts)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'user_input', ?, '{}'::jsonb)
        """,
        itemId,
        record.tripId(),
        day.id(),
        versionId,
        record.command().sequenceNo(),
        record.command().itemType(),
        reference.placeId(),
        reference.accommodationId(),
        reference.transportEventId(),
        record.command().title() == null ? reference.title() : record.command().title(),
        Timestamp.from(start),
        Timestamp.from(end),
        record.command().stayMinutes(),
        record.command().bufferAfterMinutes(),
        record.command().required(),
        record.command().memo());
  }

  private Map<ItemPair, SourceLeg> loadSourceLegs(UUID tripId, UUID versionId) {
    Map<ItemPair, SourceLeg> result = new HashMap<>();
    jdbc.query(
        """
        select from_item_id, to_item_id, transport_mode, origin_stop_id, destination_stop_id,
               route_id, mobility_route_snapshot_id, planned_departure_at, planned_arrival_at,
               walk_minutes, wait_minutes, ride_minutes, transfer_minutes, duration_minutes,
               buffer_minutes, distance_meters, estimated_fare, risk_score, facts::text
        from public.trip_legs where trip_plan_id=? and schedule_version_id=?
        """,
        rs -> {
          SourceLeg leg = sourceLeg(rs);
          result.put(new ItemPair(leg.fromItemId(), leg.toItemId()), leg);
        },
        tripId,
        versionId);
    return result;
  }

  private void copyOrDeriveLegs(
      UUID tripId,
      UUID versionId,
      List<NewItem> items,
      Map<ItemPair, SourceLeg> sourceLegs,
      String preferredMode,
      Instant transactionTime) {
    Map<UUID, List<NewItem>> byDay = new LinkedHashMap<>();
    items.stream()
        .sorted(
            java.util.Comparator.comparing(NewItem::dayId).thenComparingInt(NewItem::sequenceNo))
        .forEach(
            item -> byDay.computeIfAbsent(item.dayId(), ignored -> new ArrayList<>()).add(item));
    for (List<NewItem> dayItems : byDay.values()) {
      for (int index = 0; index + 1 < dayItems.size(); index++) {
        NewItem from = dayItems.get(index);
        NewItem to = dayItems.get(index + 1);
        SourceLeg reusable =
            from.oldId() == null || to.oldId() == null
                ? null
                : sourceLegs.get(new ItemPair(from.oldId(), to.oldId()));
        if (reusable != null
            && from.semanticallyUnchanged()
            && to.semanticallyUnchanged()
            && reusable.transportMode().equals(preferredMode)) {
          insertCopiedLeg(tripId, versionId, index + 1, from, to, reusable, transactionTime);
        } else {
          insertFallbackLeg(tripId, versionId, index + 1, from, to);
        }
      }
    }
  }

  private String preferredTransportMode(UUID tripId) {
    return jdbc
        .query(
            "select transport_mode from public.trip_transport_modes where trip_plan_id=? and is_primary order by priority limit 1",
            (rs, row) -> rs.getString("transport_mode"),
            tripId)
        .stream()
        .findFirst()
        .orElse("walk");
  }

  private void insertCopiedLeg(
      UUID tripId,
      UUID versionId,
      int sequence,
      NewItem from,
      NewItem to,
      SourceLeg leg,
      Instant transactionTime) {
    Instant departure = from.end();
    Instant arrival = departure.plusSeconds(leg.durationMinutes() * 60L);
    if (arrival.isAfter(to.start())) {
      throw ScheduleException.legIncomplete();
    }
    UUID copiedLegId = UUID.randomUUID();
    jdbc.update(
        """
        insert into public.trip_legs
          (id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no, from_item_id,
           to_item_id, transport_mode, origin_stop_id, destination_stop_id, route_id,
           mobility_route_snapshot_id, planned_departure_at, planned_arrival_at, walk_minutes,
           wait_minutes, ride_minutes, transfer_minutes, duration_minutes, buffer_minutes,
           distance_meters, estimated_fare, risk_score, facts)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """,
        copiedLegId,
        tripId,
        from.dayId(),
        versionId,
        sequence,
        from.id(),
        to.id(),
        leg.transportMode(),
        leg.originStopId(),
        leg.destinationStopId(),
        leg.routeId(),
        null,
        Timestamp.from(departure),
        Timestamp.from(arrival),
        leg.walkMinutes(),
        leg.waitMinutes(),
        leg.rideMinutes(),
        leg.transferMinutes(),
        leg.durationMinutes(),
        leg.bufferMinutes(),
        leg.distanceMeters(),
        leg.estimatedFare(),
        leg.riskScore(),
        leg.facts());
    if (leg.snapshotId() != null) {
      UUID snapshotId =
          jdbc.queryForObject(
              "select timing_jeju_planner_private.clone_planned_route_snapshot(?,?,?,?,?,?,?)",
              UUID.class,
              leg.snapshotId(),
              tripId,
              versionId,
              from.id(),
              to.id(),
              Timestamp.from(departure),
              Timestamp.from(transactionTime));
      if (snapshotId == null) throw ScheduleException.legIncomplete();
      jdbc.update(
          "update public.trip_legs set mobility_route_snapshot_id=? where id=?",
          snapshotId,
          copiedLegId);
    }
  }

  private void insertFallbackLeg(
      UUID tripId, UUID versionId, int sequence, NewItem from, NewItem to) {
    Long distance =
        jdbc
            .query(
                """
                select ceil(ST_Distance(origin.location, destination.location))::bigint as meters
                from timing_jeju_planner_private.resolve_planned_item_anchor(?, ?, ?) origin
                cross join timing_jeju_planner_private.resolve_planned_item_anchor(?, ?, ?) destination
                """,
                (rs, row) -> rs.getLong("meters"),
                from.id(),
                versionId,
                tripId,
                to.id(),
                versionId,
                tripId)
            .stream()
            .findFirst()
            .orElseThrow(ScheduleException::legIncomplete);
    int walkMinutes = Math.max(1, Math.toIntExact((distance + 49) / 50));
    Instant arrival = from.end().plusSeconds(walkMinutes * 60L);
    if (arrival.isAfter(to.start())) {
      throw ScheduleException.legIncomplete();
    }
    jdbc.update(
        """
        insert into public.trip_legs
          (id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no, from_item_id,
           to_item_id, transport_mode, planned_departure_at, planned_arrival_at, walk_minutes,
           wait_minutes, ride_minutes, transfer_minutes, duration_minutes, buffer_minutes,
           distance_meters, estimated_fare, risk_score, facts)
        values (?, ?, ?, ?, ?, ?, ?, 'walk', ?, ?, ?, 0, 0, 0, ?, 0, ?, 0, null,
                '{"derivation":"conservative_walk_v1"}'::jsonb)
        """,
        UUID.randomUUID(),
        tripId,
        from.dayId(),
        versionId,
        sequence,
        from.id(),
        to.id(),
        Timestamp.from(from.end()),
        Timestamp.from(arrival),
        walkMinutes,
        walkMinutes,
        distance);
  }

  private static SourceItem sourceItem(ResultSet rs) throws SQLException {
    return new SourceItem(
        rs.getObject("id", UUID.class),
        rs.getObject("trip_day_id", UUID.class),
        rs.getInt("sequence_no"),
        rs.getString("item_type"),
        rs.getObject("place_id", UUID.class),
        rs.getObject("accommodation_id", UUID.class),
        rs.getObject("transport_event_id", UUID.class),
        rs.getString("title"),
        rs.getTimestamp("planned_start_at").toInstant(),
        rs.getTimestamp("planned_end_at").toInstant(),
        rs.getInt("stay_minutes"),
        rs.getInt("buffer_after_minutes"),
        rs.getBoolean("required"),
        rs.getString("source"),
        rs.getString("memo"),
        rs.getString("facts"));
  }

  private static SourceLeg sourceLeg(ResultSet rs) throws SQLException {
    return new SourceLeg(
        rs.getObject("from_item_id", UUID.class),
        rs.getObject("to_item_id", UUID.class),
        rs.getString("transport_mode"),
        rs.getObject("origin_stop_id", UUID.class),
        rs.getObject("destination_stop_id", UUID.class),
        rs.getObject("route_id", UUID.class),
        rs.getObject("mobility_route_snapshot_id", UUID.class),
        rs.getTimestamp("planned_departure_at").toInstant(),
        rs.getTimestamp("planned_arrival_at").toInstant(),
        rs.getInt("walk_minutes"),
        rs.getInt("wait_minutes"),
        rs.getInt("ride_minutes"),
        rs.getInt("transfer_minutes"),
        rs.getInt("duration_minutes"),
        rs.getInt("buffer_minutes"),
        rs.getObject("distance_meters", Integer.class),
        rs.getObject("estimated_fare", Integer.class),
        rs.getObject("risk_score", Integer.class),
        rs.getString("facts"));
  }

  private static LocalTime nullableLocalTime(ResultSet rs, String column) throws SQLException {
    Time value = rs.getTime(column);
    return value == null ? null : value.toLocalTime();
  }

  private record Day(UUID id, LocalDate date, LocalTime startTime, LocalTime endTime) {}

  private record ResolvedReference(
      UUID placeId, String title, UUID accommodationId, UUID transportEventId) {
    private ResolvedReference withTitle(String replacement) {
      return new ResolvedReference(placeId, replacement, accommodationId, transportEventId);
    }
  }

  private record ItemPair(UUID from, UUID to) {}

  private record NewItem(
      UUID id,
      UUID oldId,
      UUID dayId,
      int sequenceNo,
      String itemType,
      UUID placeId,
      Instant start,
      Instant end,
      boolean semanticallyUnchanged) {}

  private record SourceItem(
      UUID id,
      UUID dayId,
      int sequenceNo,
      String itemType,
      UUID placeId,
      UUID accommodationId,
      UUID transportEventId,
      String title,
      Instant start,
      Instant end,
      int stayMinutes,
      int bufferAfterMinutes,
      boolean required,
      String source,
      String memo,
      String facts) {
    NewItem asNew(UUID newId, int sequence) {
      return asNew(newId, sequence, this);
    }

    NewItem asNew(UUID newId, int sequence, SourceItem original) {
      boolean unchanged =
          original != null
              && itemType.equals(original.itemType())
              && java.util.Objects.equals(placeId, original.placeId())
              && start.equals(original.start())
              && end.equals(original.end());
      return new NewItem(newId, id, dayId, sequence, itemType, placeId, start, end, unchanged);
    }

    SourceItem withPosition(
        UUID replacementDayId, int replacementSequence, Instant replacementStart) {
      return new SourceItem(
          id,
          replacementDayId,
          replacementSequence,
          itemType,
          placeId,
          accommodationId,
          transportEventId,
          title,
          replacementStart,
          replacementStart.plusSeconds(stayMinutes * 60L),
          stayMinutes,
          bufferAfterMinutes,
          required,
          source,
          memo,
          facts);
    }
  }

  @FunctionalInterface
  private interface ItemEditor<T> {
    List<SourceItem> apply(List<SourceItem> items);
  }

  private record SourceLeg(
      UUID fromItemId,
      UUID toItemId,
      String transportMode,
      UUID originStopId,
      UUID destinationStopId,
      UUID routeId,
      UUID snapshotId,
      Instant departure,
      Instant arrival,
      int walkMinutes,
      int waitMinutes,
      int rideMinutes,
      int transferMinutes,
      int durationMinutes,
      int bufferMinutes,
      Integer distanceMeters,
      Integer estimatedFare,
      Integer riskScore,
      String facts) {}
}
