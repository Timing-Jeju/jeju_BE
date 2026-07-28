import AppKit
import CoreGraphics
import Foundation
import PDFKit

let templatePath = "/Users/josephuk77/Downloads/(양식1)『2026 관광데이터 활용 공모전』 제안서_팀명(대표자명) (1).pdf"
let outputPath = CommandLine.arguments.count > 1
  ? CommandLine.arguments[1]
  : "docs/proposals/timing-jeju-final-template-classified.pdf"

let page = CGRect(x: 0, y: 0, width: 595, height: 842)
let font = NSFont(name: "AppleSDGothicNeo-Regular", size: 12) ?? NSFont.systemFont(ofSize: 12)
let color = NSColor(calibratedWhite: 0.06, alpha: 1)

struct Box {
  let x: CGFloat
  let top: CGFloat
  let width: CGFloat
  let height: CGFloat
}

// Empty writing areas inside the provided template. These coordinates preserve
// the original title, guide text, dividers, and section boxes.
let section1 = Box(x: 61, top: 242, width: 475, height: 70)
let section2 = Box(x: 61, top: 382, width: 475, height: 95)
let section3 = Box(x: 61, top: 523, width: 475, height: 63)
let section4 = Box(x: 61, top: 642, width: 475, height: 56)

struct PageText {
  let s1: String
  let s2: String
  let s3: String
  let s4: String
}

let pages: [PageText] = [
  PageText(
    s1: "1. 서비스 기획 배경: 제주는 관광지가 넓게 분산되어 버스 배차·환승·정류장 도보 이동이 일정 실패로 이어지기 쉽다.\n2. 서비스 필요성: 지도 앱은 구간 경로를 알려주지만, 하루 일정 기준으로 언제 출발해야 하는지와 놓쳤을 때 대안을 판단하기 어렵다.",
    s2: "1. 기획 서비스 소개: 타이밍제주는 제주 뚜벅이를 위한 AI 교통 동기화 일정 매니저이다.\n2. 주요 기능: TourAPI 장소 매칭, 가까운 정류장 탐색, 시간표 기반 출발 권장 시각, 일정 안전도, 버스 놓침 시 주변 추천, 지연 시 AI 재일정.\n3. 차별성: 관광지 추천이 아니라 여행 당일의 일정 운영을 돕는다.\n4. 지역 특화: 제주 동쪽 코리도어 MVP 후 전역 확장.",
    s3: "1. 활용 예정 한국관광공사 OpenAPI: 국문관광정보 TourAPI.\n2. 활용 방식: 키워드검색·위치기반·이미지·공통·소개정보로 관광지 후보를 만들고, 제주 정류소·노선·시간표와 결합해 실행 가능한 일정을 계산한다.",
    s4: "1. 개발 서비스 향후 발전방향: 공모전 기간에는 제주 동쪽 MVP를 완성하고, 이후 제주 전역 확대, 실시간 보정, 다국어 안내, RTO 대시보드로 확장한다."
  ),
  PageText(
    s1: "1. 서비스 기획 배경: 2024년 제주 방문 관광객은 약 1,378만 명 규모이고, 비짓제주는 2024.1.1~11.30 누적 531만 6,274명이 방문했다.\n2. 서비스 필요성: 관광정보 탐색 수요는 크지만, 버스 시간까지 고려해 하루 일정을 운영하는 서비스는 부족하다.",
    s2: "1. 서비스 소개: 사용자가 ‘공항→함덕→월정리→성산’처럼 입력하면 버스 중심 일정으로 재구성한다.\n2. 주요 기능: 입력 장소 후보 확인, 정류장 자동 탐색, 버스 후보 계산, 놓쳤을 때 추가 대기 시간 표시, 남는 시간 추천.\n3. 차별성: 단일 이동경로가 아니라 체류시간과 다음 버스까지 함께 관리한다.\n4. 지역 특화: 제주 대중교통 여행자의 실제 불편을 직접 해결한다.",
    s3: "1. TourAPI 활용: 관광지명, 주소, 좌표, 이미지, 소개 정보를 일정 카드와 추천 카드에 표시한다.\n2. 제주 버스 활용: 정류소·노선·시간표로 출발 권장 시각과 구간별 위험도를 계산한다. AI는 계산된 결과만 설명한다.",
    s4: "1. MVP 방향: 실시간 데이터가 없어도 시간표 기반 출발 권장 시각과 대기 위험 계산은 구현 가능하다. 이후 실시간 도착 정보가 확보되면 보정값을 더한다."
  ),
  PageText(
    s1: "1. 문제 정의: 제주 뚜벅이는 관광지 정보 부족보다 이동 타이밍 실패에 더 취약하다. 버스 한 대를 놓치면 다음 일정 전체가 밀린다.\n2. 실제 경험/POC: 20살 제주 여행 중 긴 배차로 최대 1시간 가까이 기다렸고, POC에서도 구간별 약 42~48분 대기 위험이 확인됐다.",
    s2: "1. 주요 기능: 라이브 여행 모드에서 초록·노랑·빨강 상태로 현재 일정 위험을 보여준다.\n2. 알림: 출발 준비, 정류장 이동, 위험, 대체안 알림을 도보 시간+안전버퍼+버스 시간 기준으로 제공한다.\n3. 남는 시간 추천: 다음 버스 전까지 실제 가능한 주변 관광지·카페·포토스팟만 제안한다.\n4. AI 재일정: 지연 시 필수 방문지는 유지하고 선택 일정을 줄인다.",
    s3: "1. OpenAI API 역할: 자연어 일정 입력을 장소·시간·취향으로 구조화하고, 계산된 위험도를 이해하기 쉬운 문장으로 설명한다.\n2. 정확도 원칙: 버스 시간과 경로 가능 여부는 AI가 생성하지 않고 서버 계산 엔진이 담당한다.",
    s4: "1. 고도화 방향: 제주 전역의 정류장·노선·시간표를 단계적으로 확대하고, 실제 버스 위치 또는 도착 예정 데이터가 확보되면 실시간 보정을 적용한다."
  ),
  PageText(
    s1: "1. 서비스 필요성: 사용자는 지금 더 머물러도 되는지, 지금 나가야 하는지, 버스를 놓치면 무엇을 포기해야 하는지 즉시 알아야 한다.\n2. 안전도 점수: 경로 성립, 대기 위험, 도보 시간, 환승 여유, 대체 노선 수를 반영해 사용자가 일정 난이도를 이해하게 한다.",
    s2: "1. 차별성: 지도 앱은 A에서 B까지의 길을 알려주지만, 타이밍제주는 하루 전체 일정의 버스 타이밍을 관리한다.\n2. 관광 추천 앱은 가까운 장소를 보여주지만, 타이밍제주는 남은 시간 안에 실제 가능한 장소만 추천한다.\n3. AI 여행 플래너의 환각 위험은 서버 계산 결과만 설명하게 하여 줄인다.\n4. 지역 특화: 제주 버스 대기 시간을 관광 경험으로 전환한다.",
    s3: "1. 데이터 활용 방식: TourAPI 위치기반 관광정보로 현재 위치 반경 후보를 조회하고, 후보별 이동 시간·예상 체류 시간·다음 버스 안전 여유를 계산한다.\n2. 추천 필터: 남은 시간에 맞지 않거나 다음 일정 위험을 키우는 후보는 제외한다.",
    s4: "1. 발전 방향: 외국인 관광객을 위해 한국어 일정 안내를 영어·중국어·일본어로 제공한다. 버스 탑승·하차 알림과 위험 안내도 다국어로 확장한다."
  ),
  PageText(
    s1: "1. 공모전 적합성: 공사 OpenAPI 활용 신규 관광 서비스라는 조건에 맞고, 제주 지역 특화 서비스로 RTO 특별상 취지에도 부합한다.\n2. 기대 필요성: 관광정보를 단순 조회가 아니라 이동 의사결정 데이터로 활용해 대기 시간과 일정 실패를 줄인다.",
    s2: "1. 서비스 완성도 계획: 공모전 MVP는 제주공항, 함덕, 월정리, 성산일출봉, 섭지코지 중심으로 구현한다.\n2. 핵심 흐름: 일정 입력→TourAPI 매칭→정류장 탐색→시간표 계산→안전도 표시→주변 추천→재일정.\n3. 차별성: 구현 가능한 데이터만 사용해 과장 없이 기능심사 대응이 가능하다.\n4. 지역 특화: 제주 단일 지역 문제에 집중해 완성도와 구체성을 높인다.",
    s3: "1. 데이터 활용 적절성: TourAPI 15종 약 26만 건 관광정보를 장소 매칭과 주변 추천의 핵심 데이터로 사용한다.\n2. OpenAI API: 자연어 일정 파싱과 위험 설명에 활용하고, 시간 계산은 서버 엔진이 담당해 신뢰도를 확보한다.",
    s4: "1. 기대효과: 사용자는 렌터카 없이도 예측 가능한 제주 일정을 운영하고, 지역은 관광객 분산 방문을 유도할 수 있다. 향후 체류시간 학습과 RTO 이동 불편 구간 분석으로 확장한다."
  ),
]

final class Renderer {
  let ctx: CGContext
  let data = NSMutableData()

  init() {
    var mediaBox = page
    let consumer = CGDataConsumer(data: data as CFMutableData)!
    ctx = CGContext(consumer: consumer, mediaBox: &mediaBox, nil)!
  }

  func save(to path: String) {
    ctx.closePDF()
    data.write(toFile: path, atomically: true)
  }

  func beginTemplatePage() {
    ctx.beginPDFPage(nil)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
    ctx.saveGState()
    ctx.setFillColor(NSColor.white.cgColor)
    ctx.fill(page)
    ctx.restoreGState()

    if let doc = PDFDocument(url: URL(fileURLWithPath: templatePath)),
       let templatePage = doc.page(at: 0) {
      templatePage.draw(with: .mediaBox, to: ctx)
    }
  }

  func endTemplatePage() {
    NSGraphicsContext.restoreGraphicsState()
    ctx.endPDFPage()
  }

  func rect(_ box: Box) -> CGRect {
    CGRect(x: box.x, y: page.height - box.top - box.height, width: box.width, height: box.height)
  }

  func draw(_ text: String, in box: Box) {
    let style = NSMutableParagraphStyle()
    style.minimumLineHeight = 14.0
    style.maximumLineHeight = 14.0
    style.lineBreakMode = .byWordWrapping
    let attributed = NSAttributedString(string: text, attributes: [
      .font: font,
      .foregroundColor: color,
      .paragraphStyle: style,
    ])
    let bounds = attributed.boundingRect(
      with: CGSize(width: box.width, height: 10_000),
      options: [.usesLineFragmentOrigin, .usesFontLeading]
    )
    if ceil(bounds.height) > box.height {
      fputs("WARN: text exceeds box by \(ceil(bounds.height - box.height))pt\n", stderr)
    }
    attributed.draw(with: rect(box), options: [.usesLineFragmentOrigin, .usesFontLeading])
  }
}

let renderer = Renderer()
for pageText in pages {
  renderer.beginTemplatePage()
  renderer.draw(pageText.s1, in: section1)
  renderer.draw(pageText.s2, in: section2)
  renderer.draw(pageText.s3, in: section3)
  renderer.draw(pageText.s4, in: section4)
  renderer.endTemplatePage()
}
renderer.save(to: outputPath)
