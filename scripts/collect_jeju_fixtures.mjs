import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const ROOT = path.resolve(import.meta.dirname, "..");
const ENV_PATH = path.join(ROOT, "env", ".env");
const FIXTURE_DIR = path.join(ROOT, "fixtures");
const TOUR_BASE = "https://apis.data.go.kr/B551011/KorService2";
const TAGO_BUS_STOP_BASE =
  "https://apis.data.go.kr/1613000/BusSttnInfoInqireService";
const TAGO_BUS_ARRIVAL_BASE =
  "https://apis.data.go.kr/1613000/ArvlInfoInqireService";

const JEJU_AREA_CODE = "39";
const JEJU_CITY_CODE = "39";

const TARGET_PLACES = [
  {
    placeId: "place_jeju_airport",
    keyword: "제주국제공항",
    fallbackName: "제주국제공항",
    fallbackLat: 33.5066,
    fallbackLng: 126.493,
    category: "transport_hub",
    fallbackStopKeywords: ["제주국제공항"],
  },
  {
    placeId: "place_seongsan_ilchulbong",
    keyword: "성산일출봉",
    fallbackName: "성산일출봉",
    fallbackLat: 33.4581,
    fallbackLng: 126.9425,
    category: "tourist_attraction",
    fallbackStopKeywords: ["성산일출봉", "성산일출봉입구"],
  },
  {
    placeId: "place_seopjikoji",
    keyword: "섭지코지",
    fallbackName: "섭지코지",
    fallbackLat: 33.4239,
    fallbackLng: 126.9305,
    category: "tourist_attraction",
    fallbackStopKeywords: ["섭지코지", "신양리"],
  },
];

function parseEnv(text) {
  return Object.fromEntries(
    text
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith("#"))
      .map((line) => {
        const index = line.indexOf("=");
        return [line.slice(0, index), line.slice(index + 1)];
      }),
  );
}

async function loadEnv() {
  const text = await readFile(ENV_PATH, "utf8");
  return parseEnv(text);
}

function requireKey(env, name) {
  const value = env[name]?.trim();
  if (!value) {
    throw new Error(`${name} is missing in ${ENV_PATH}`);
  }
  return value;
}

function buildUrl(base, operation, params, serviceKey) {
  const query = new URLSearchParams(params);
  return `${base}/${operation}?serviceKey=${serviceKey}&${query.toString()}`;
}

function asArray(value) {
  if (!value) return [];
  return Array.isArray(value) ? value : [value];
}

function readItems(payload) {
  return asArray(payload?.response?.body?.items?.item);
}

async function fetchJson(url, label) {
  const response = await fetch(url);
  const text = await response.text();

  if (!response.ok) {
    throw new Error(`${label} failed with HTTP ${response.status}: ${text}`);
  }

  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`${label} returned non-JSON response: ${text.slice(0, 240)}`);
  }
}

async function tourRequest(operation, params, serviceKey, label) {
  return fetchJson(
    buildUrl(
      TOUR_BASE,
      operation,
      {
        MobileOS: "ETC",
        MobileApp: "TimingJeju",
        _type: "json",
        ...params,
      },
      serviceKey,
    ),
    label,
  );
}

async function tagoRequest(base, operation, params, serviceKey, label) {
  return fetchJson(
    buildUrl(
      base,
      operation,
      {
        _type: "json",
        ...params,
      },
      serviceKey,
    ),
    label,
  );
}

async function collectPlace(target, env) {
  const tourKey = requireKey(env, "TOUR_API_SERVICE_KEY");
  const detailKey = requireKey(env, "TOUR_DETAIL_API_SERVICE_KEY");

  const searchPayload = await tourRequest(
    "searchKeyword2",
    {
      numOfRows: "5",
      pageNo: "1",
      arrange: "A",
      areaCode: JEJU_AREA_CODE,
      keyword: target.keyword,
    },
    tourKey,
    `tour search ${target.keyword}`,
  );

  const found =
    readItems(searchPayload).find((item) => item.title === target.keyword) ??
    readItems(searchPayload)[0];

  const contentId = found?.contentid ?? null;
  const contentTypeId = found?.contenttypeid ?? null;

  let detail = {};
  let images = [];
  if (contentId) {
    const detailPayload = await tourRequest(
      "detailCommon2",
      {
        contentId,
        contentTypeId,
        defaultYN: "Y",
        firstImageYN: "Y",
        areacodeYN: "Y",
        catcodeYN: "Y",
        addrinfoYN: "Y",
        mapinfoYN: "Y",
        overviewYN: "Y",
      },
      detailKey,
      `tour detail ${target.keyword}`,
    );
    detail = readItems(detailPayload)[0] ?? {};

    const imagePayload = await tourRequest(
      "detailImage2",
      {
        contentId,
        imageYN: "Y",
        subImageYN: "Y",
        numOfRows: "5",
        pageNo: "1",
      },
      detailKey,
      `tour images ${target.keyword}`,
    );
    images = readItems(imagePayload).map((image) => ({
      originUrl: image.originimgurl ?? null,
      smallUrl: image.smallimageurl ?? null,
    }));
  }

  return {
    placeId: target.placeId,
    contentId,
    contentTypeId,
    name: detail.title ?? found?.title ?? target.fallbackName,
    category: target.category,
    fallbackStopKeywords: target.fallbackStopKeywords,
    address: detail.addr1 ?? found?.addr1 ?? null,
    addressDetail: detail.addr2 ?? found?.addr2 ?? null,
    latitude: Number(detail.mapy ?? found?.mapy ?? target.fallbackLat),
    longitude: Number(detail.mapx ?? found?.mapx ?? target.fallbackLng),
    imageUrl: detail.firstimage ?? found?.firstimage ?? null,
    thumbnailUrl: detail.firstimage2 ?? found?.firstimage2 ?? null,
    images,
    overview: detail.overview ?? null,
    sourceApi: {
      provider: "한국관광공사",
      service: "국문 관광정보 서비스_GW",
      operations: ["searchKeyword2", "detailCommon2", "detailImage2"],
    },
    nearbyStopIds: [],
  };
}

function distanceMeters(a, b) {
  const radius = 6371000;
  const toRad = (deg) => (deg * Math.PI) / 180;
  const dLat = toRad(b.latitude - a.latitude);
  const dLng = toRad(b.longitude - a.longitude);
  const lat1 = toRad(a.latitude);
  const lat2 = toRad(b.latitude);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return Math.round(2 * radius * Math.asin(Math.sqrt(h)));
}

async function collectStopsForPlace(place, env) {
  const stopKey = requireKey(env, "TAGO_BUS_STOP_SERVICE_KEY");
  const payload = await tagoRequest(
    TAGO_BUS_STOP_BASE,
    "getCrdntPrxmtSttnList",
    {
      gpsLati: String(place.latitude),
      gpsLong: String(place.longitude),
    },
    stopKey,
    `nearby bus stops ${place.name}`,
  );

  let stopItems = readItems(payload);
  if (stopItems.length === 0) {
    for (const keyword of place.fallbackStopKeywords ?? [place.name]) {
      const searchPayload = await tagoRequest(
        TAGO_BUS_STOP_BASE,
        "getSttnNoList",
        {
          cityCode: JEJU_CITY_CODE,
          nodeNm: keyword,
          numOfRows: "10",
          pageNo: "1",
        },
        stopKey,
        `bus stop search ${keyword}`,
      );
      stopItems = readItems(searchPayload);
      if (stopItems.length > 0) break;
    }
  }

  return stopItems.slice(0, 2).map((stop, index) => {
    const latitude = Number(stop.gpslati ?? stop.gpsLati ?? stop.lat);
    const longitude = Number(stop.gpslong ?? stop.gpsLong ?? stop.lng);
    const distance = Number.isFinite(latitude)
      ? distanceMeters(place, { latitude, longitude })
      : null;

    return {
      stopId: `stop_${place.placeId.replace(/^place_/, "")}_${index + 1}`,
      nodeId: stop.nodeid ?? stop.nodeId ?? null,
      nodeName: stop.nodenm ?? stop.nodeNm ?? stop.nodename ?? null,
      nodeNo: stop.nodeno ?? stop.nodeNo ?? null,
      latitude,
      longitude,
      linkedPlaceIds: [place.placeId],
      distanceMetersFromPlace: distance,
      walkMinutesFromPlace: distance == null ? null : Math.max(1, Math.ceil(distance / 70)),
      sourceApi: {
        provider: "TAGO",
        service: "버스정류소정보 API",
        operation: "getCrdntPrxmtSttnList",
      },
    };
  });
}

async function collectArrivalsForStop(stop, env) {
  const arrivalKey = requireKey(env, "TAGO_BUS_ARRIVAL_SERVICE_KEY");
  if (!stop.nodeId) return [];

  const payload = await tagoRequest(
    TAGO_BUS_ARRIVAL_BASE,
    "getSttnAcctoArvlPrearngeInfoList",
    {
      cityCode: JEJU_CITY_CODE,
      nodeId: stop.nodeId,
      numOfRows: "10",
      pageNo: "1",
    },
    arrivalKey,
    `bus arrivals ${stop.nodeName}`,
  );

  return readItems(payload).map((arrival, index) => ({
    timetableId: `arrival_${stop.stopId}_${index + 1}`,
    stopId: stop.stopId,
    nodeId: stop.nodeId,
    routeId: arrival.routeid ?? arrival.routeId ?? null,
    routeNo: arrival.routeno ?? arrival.routeNo ?? null,
    direction: arrival.routetp ?? arrival.routeTp ?? null,
    estimatedArrivalSeconds: Number(arrival.arrtime ?? arrival.arrTime ?? 0) || null,
    estimatedArrivalMinutes:
      Number(arrival.arrtime ?? arrival.arrTime ?? 0) > 0
        ? Math.ceil(Number(arrival.arrtime ?? arrival.arrTime) / 60)
        : null,
    remainingStops: Number(arrival.arrprevstationcnt ?? arrival.arrPrevStationCnt ?? 0) || null,
    vehicleType: arrival.vehicletp ?? arrival.vehicleTp ?? null,
    sourceApi: {
      provider: "TAGO",
      service: "버스도착정보 API",
      operation: "getSttnAcctoArvlPrearngeInfoList",
    },
    cacheTtlSeconds: 30,
  }));
}

async function main() {
  const env = await loadEnv();
  await mkdir(FIXTURE_DIR, { recursive: true });

  console.log("Collecting places from Korea Tourism Organization API...");
  const places = [];
  for (const target of TARGET_PLACES) {
    places.push(await collectPlace(target, env));
  }

  console.log("Collecting nearby bus stops from TAGO...");
  const stops = [];
  for (const place of places) {
    const nearbyStops = await collectStopsForPlace(place, env);
    place.nearbyStopIds = nearbyStops.map((stop) => stop.stopId);
    stops.push(...nearbyStops);
  }

  console.log("Collecting bus arrival snapshots from TAGO...");
  const arrivals = [];
  for (const stop of stops) {
    arrivals.push(...(await collectArrivalsForStop(stop, env)));
  }

  await writeFile(
    path.join(FIXTURE_DIR, "place_fixture.json"),
    `${JSON.stringify(places, null, 2)}\n`,
  );
  await writeFile(
    path.join(FIXTURE_DIR, "stop_fixture.json"),
    `${JSON.stringify(stops, null, 2)}\n`,
  );
  await writeFile(
    path.join(FIXTURE_DIR, "bus_timetable_fixture.json"),
    `${JSON.stringify(arrivals, null, 2)}\n`,
  );

  console.log(`Wrote ${places.length} places.`);
  console.log(`Wrote ${stops.length} nearby stops.`);
  console.log(`Wrote ${arrivals.length} bus arrival records.`);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
