import { readFile } from "node:fs/promises";
import path from "node:path";

const ROOT = path.resolve(import.meta.dirname, "..");
const FIXTURE_DIR = path.join(ROOT, "fixtures");

async function readJson(name) {
  return JSON.parse(await readFile(path.join(FIXTURE_DIR, name), "utf8"));
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function main() {
  const places = await readJson("place_fixture.json");
  const stops = await readJson("stop_fixture.json");
  const timetables = await readJson("bus_timetable_fixture.json");

  assert(Array.isArray(places), "place_fixture.json must be an array");
  assert(Array.isArray(stops), "stop_fixture.json must be an array");
  assert(Array.isArray(timetables), "bus_timetable_fixture.json must be an array");
  assert(places.length === 3, "place_fixture.json must contain 3 target places");

  const placeIds = new Set(places.map((place) => place.placeId));
  const stopIds = new Set(stops.map((stop) => stop.stopId));

  for (const place of places) {
    assert(place.placeId, "every place needs placeId");
    assert(place.name, `${place.placeId} needs name`);
    assert(Number.isFinite(place.latitude), `${place.placeId} needs latitude`);
    assert(Number.isFinite(place.longitude), `${place.placeId} needs longitude`);
    assert(
      Array.isArray(place.nearbyStopIds) && place.nearbyStopIds.length > 0,
      `${place.placeId} needs at least one nearby stop`,
    );
    for (const stopId of place.nearbyStopIds) {
      assert(stopIds.has(stopId), `${place.placeId} references missing ${stopId}`);
    }
  }

  for (const stop of stops) {
    assert(stop.stopId, "every stop needs stopId");
    assert(stop.nodeId, `${stop.stopId} needs nodeId`);
    assert(stop.nodeName, `${stop.stopId} needs nodeName`);
    assert(Number.isFinite(stop.latitude), `${stop.stopId} needs latitude`);
    assert(Number.isFinite(stop.longitude), `${stop.stopId} needs longitude`);
    assert(Array.isArray(stop.linkedPlaceIds), `${stop.stopId} needs linkedPlaceIds`);
    for (const placeId of stop.linkedPlaceIds) {
      assert(placeIds.has(placeId), `${stop.stopId} references missing ${placeId}`);
    }
  }

  for (const timetable of timetables) {
    assert(timetable.stopId, "every timetable row needs stopId");
    assert(stopIds.has(timetable.stopId), `${timetable.timetableId} references missing stop`);
  }

  console.log("Fixture validation passed.");
  console.log(`places=${places.length}, stops=${stops.length}, timetables=${timetables.length}`);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});

