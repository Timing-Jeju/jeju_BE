import AppKit
import CoreGraphics
import Foundation
import PDFKit

let blankFormPath = "/Users/josephuk77/Downloads/(양식1)『2026 관광데이터 활용 공모전』 제안서_팀명(대표자명) (1).pdf"
let outputPath = CommandLine.arguments.count > 1
  ? CommandLine.arguments[1]
  : "docs/proposals/timing-jeju-each-box-12pt-5page.pdf"

let page = CGRect(x: 0, y: 0, width: 595, height: 842)
let bodyFont = NSFont(name: "AppleSDGothicNeo-Regular", size: 12) ?? NSFont.systemFont(ofSize: 12)
let bodyBold = NSFont(name: "AppleSDGothicNeo-Bold", size: 12) ?? NSFont.boldSystemFont(ofSize: 12)
let dark = NSColor(calibratedWhite: 0.08, alpha: 1)
let blue = NSColor(calibratedRed: 0.02, green: 0.22, blue: 0.45, alpha: 1)

struct Box {
  let x: CGFloat
  let top: CGFloat
  let width: CGFloat
  let height: CGFloat
}

let backgroundBox = Box(x: 61, top: 242, width: 475, height: 70)
let overviewBox = Box(x: 61, top: 382, width: 475, height: 95)
let dataBox = Box(x: 61, top: 523, width: 475, height: 63)
let growthBox = Box(x: 61, top: 642, width: 475, height: 56)

final class Renderer {
  let ctx: CGContext
  let data = NSMutableData()
  var pageNo = 0

  init() {
    var box = page
    let consumer = CGDataConsumer(data: data as CFMutableData)!
    ctx = CGContext(consumer: consumer, mediaBox: &box, nil)!
  }

  func save(to path: String) {
    ctx.closePDF()
    data.write(toFile: path, atomically: true)
  }

  func beginFormPage() {
    pageNo += 1
    ctx.beginPDFPage(nil)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)

    ctx.saveGState()
    ctx.setFillColor(NSColor.white.cgColor)
    ctx.fill(page)
    ctx.restoreGState()

    if let doc = PDFDocument(url: URL(fileURLWithPath: blankFormPath)),
       let formPage = doc.page(at: 0) {
      formPage.draw(with: .mediaBox, to: ctx)
    }
  }

  func endPage() {
    drawText("(\(pageNo)/5)", in: Box(x: 496, top: 710, width: 40, height: 16), font: bodyFont, color: NSColor.darkGray, align: .right, lineHeight: 14.0)
    NSGraphicsContext.restoreGraphicsState()
    ctx.endPDFPage()
  }

  func rect(_ box: Box) -> CGRect {
    CGRect(x: box.x, y: page.height - box.top - box.height, width: box.width, height: box.height)
  }

  func attrs(font: NSFont, color: NSColor, align: NSTextAlignment, lineHeight: CGFloat) -> [NSAttributedString.Key: Any] {
    let style = NSMutableParagraphStyle()
    style.alignment = align
    style.minimumLineHeight = lineHeight
    style.maximumLineHeight = lineHeight
    style.lineBreakMode = .byWordWrapping
    return [.font: font, .foregroundColor: color, .paragraphStyle: style]
  }

  func drawText(_ text: String, in box: Box, font: NSFont = bodyFont, color: NSColor = dark, align: NSTextAlignment = .left, lineHeight: CGFloat = 14.0) {
    let attributed = NSAttributedString(string: text, attributes: attrs(font: font, color: color, align: align, lineHeight: lineHeight))
    let bounds = attributed.boundingRect(with: CGSize(width: box.width, height: 10_000), options: [.usesLineFragmentOrigin, .usesFontLeading])
    if ceil(bounds.height) > box.height {
      fputs("WARN page \(pageNo): text height \(ceil(bounds.height)) exceeds box height \(box.height)\n", stderr)
    }
    attributed.draw(with: rect(box), options: [.usesLineFragmentOrigin, .usesFontLeading])
  }
}

struct PageContent {
  let background: String
  let overview: String
  let data: String
  let growth: String
}

let contents: [PageContent] = [
  PageContent(
    background: "1. 서비스 기획 배경: 제주는 관광지가 넓게 분산되어 버스 배차·환승·정류장 도보 이동이 일정 실패로 이어진다.\n2. 서비스 필요성: TourAPI 관광정보와 제주 버스 시간표를 결합해 출발 시각·대기 위험·대체 일정을 안내한다.",
    overview: "1. 기획 서비스 소개: 제주 뚜벅이를 위한 AI 교통 동기화 일정 매니저 ‘타이밍제주’.\n2. 주요 기능: 관광지 매칭, 정류장 탐색, 출발 권장 시각, 일정 안전도, 남는 시간 추천, AI 재일정.\n3. 차별성: 단일 경로가 아니라 하루 전체 버스 타이밍을 관리한다.\n4. 지역 특화: 제주 동쪽 코리도어 MVP 후 전역 확장.",
    data: "1. 활용 OpenAPI: 한국관광공사 국문관광정보 TourAPI.\n2. 활용 방식: 키워드검색·위치기반·이미지·공통정보로 관광지 후보를 만들고, 제주 버스 데이터로 실행 가능성을 계산한다.",
    growth: "1. 향후 발전방향: 공모전 기간에는 제주 동쪽 MVP를 완성한다. 이후 제주 전역 확대, 실시간 보정, 다국어 안내, RTO 대시보드로 발전시켜 지속 가능한 서비스로 확장한다."
  ),
  PageContent(
    background: "1. 서비스 기획 배경: 2024년 제주 방문 관광객은 약 1,378만 명 규모이며, 비짓제주는 2024.1.1~11.30 누적 531만 6,274명이 방문했다.\n2. 서비스 필요성: 온라인 관광정보 수요는 크지만 버스 시간까지 맞춰 하루 일정을 운영하는 서비스는 부족하다.",
    overview: "1. 소개: 사용자가 ‘공항→함덕→월정리→성산’처럼 입력하면 실행 가능한 버스 중심 일정으로 변환한다.\n2. 주요 기능: 장소명 후보 확인, 가까운 정류장 자동 탐색, 버스 후보 계산, 놓쳤을 때 추가 대기 시간 표시.\n3. 차별성: 지도 앱의 경로 안내와 달리 관광 중 머물러도 되는 시간을 계속 계산한다.\n4. 지역 특화: 제주 대중교통 여행자의 실제 불편을 해결한다.",
    data: "1. TourAPI 활용: 관광지명, 주소, 좌표, 이미지, 소개 정보를 일정 카드에 표시한다.\n2. 제주 버스 활용: 정류소·노선·시간표로 출발 권장 시각과 구간별 위험도를 계산한다. AI는 계산값만 설명한다.",
    growth: "1. 향후 발전방향: MVP에서는 시간표 기반 안내를 먼저 구현한다. 실시간 도착 정보가 확보되면 보정값을 더해 정확도를 높이고, 알림 기능을 추가한다."
  ),
  PageContent(
    background: "1. 서비스 기획 배경: 20살 제주 뚜벅이 여행 중 배차 간격을 놓쳐 최대 1시간 가까이 기다린 경험에서 출발했다.\n2. 서비스 필요성: 한 번의 대기는 다음 관광지 체류시간, 식사, 숙소 도착까지 밀리게 하므로 사전 위험 안내가 필요하다.",
    overview: "1. 소개: 타이밍제주는 ‘어디 갈지’보다 ‘언제 나와야 일정이 무너지지 않는지’를 관리한다.\n2. 주요 기능: 일정 안전도 점수는 경로 성립, 대기 위험, 도보 시간, 환승 여유, 대체 노선 수를 반영한다.\n3. 차별성: OpenAI는 버스 시간을 만들지 않고 서버 계산 결과를 설명한다.\n4. 지역 특화: 제주 동쪽 관광권에서 기능심사 시연이 가능하다.",
    data: "1. 활용 OpenAPI: TourAPI 키워드검색, 위치기반 관광정보, 이미지정보, 공통·소개정보.\n2. 활용 방식: 남은 시간 안에 실제 가능한 주변 관광지·카페·포토스팟 후보를 필터링하고 이미지 카드로 보여준다.",
    growth: "1. 향후 발전방향: 일정 지연 시 필수 방문지는 유지하고 선택 관광지를 줄이거나 순서를 바꾼다. 사용자의 우선순위와 실제 체류시간을 학습해 재일정 기능을 고도화한다."
  ),
  PageContent(
    background: "1. 서비스 기획 배경: 제주 뚜벅이는 관광지 정보보다 이동 실패에 더 취약하다. 특히 정류장까지 걷는 시간과 배차 간격이 핵심 변수다.\n2. 서비스 필요성: 사용자는 지금 더 머물러도 되는지, 지금 나가야 하는지, 놓치면 무엇을 포기해야 하는지 바로 알아야 한다.",
    overview: "1. 소개: 라이브 여행 모드에서 초록·노랑·빨강 상태로 현재 일정 위험을 보여준다.\n2. 주요 기능: 출발 준비 알림, 정류장 이동 알림, 위험 알림, 대체안 알림을 제공한다.\n3. 차별성: 남는 시간을 단순 주변 추천이 아니라 다음 버스 전까지 가능한 추천으로 제한한다.\n4. 지역 특화: 제주 버스 대기 시간을 관광 경험으로 전환한다.",
    data: "1. 활용 OpenAPI: TourAPI 위치기반 관광정보로 현재 위치 반경 후보를 조회한다.\n2. 활용 방식: 후보별 이동 시간·체류 예상 시간·다음 버스 안전 여유를 계산해 가능한 후보만 추천한다. 우천 시 실내 후보를 우선한다.",
    growth: "1. 향후 발전방향: 외국인 관광객을 위해 한국어 일정 안내를 영어·중국어·일본어로 제공한다. 버스 탑승·하차 알림도 다국어로 확장한다."
  ),
  PageContent(
    background: "1. 서비스 기획 배경: 공모전은 공사 OpenAPI 활용 신규 관광 서비스를 요구하며, 제주 지역 특화 서비스는 RTO 특별상 취지와도 맞다.\n2. 서비스 필요성: 타이밍제주는 관광정보를 조회에서 끝내지 않고 실제 이동 의사결정 데이터로 활용한다.",
    overview: "1. 소개: 공모전 MVP는 제주공항, 함덕, 월정리, 성산일출봉, 섭지코지 중심으로 구현한다.\n2. 주요 기능: 일정 입력→TourAPI 매칭→정류장 탐색→시간표 계산→안전도 표시→주변 추천→재일정.\n3. 차별성: 구현 가능한 데이터만 사용해 과장 없이 기능심사 대응이 가능하다.\n4. 지역 특화: 제주 단일 지역 문제에 집중해 완성도와 구체성을 높인다.",
    data: "1. 활용 OpenAPI: TourAPI 15종 약 26만 건 관광정보를 장소 매칭과 주변 추천의 핵심 데이터로 활용한다.\n2. 활용 방식: OpenAI API는 자연어 일정 파싱과 위험 설명에 쓰고, 시간 계산은 서버 엔진이 담당해 정확도를 확보한다.",
    growth: "1. 향후 발전방향: 제주 전역 확대, 실시간 보정, 사용자 체류시간 학습, RTO 이동 불편 구간 분석 대시보드로 발전시켜 공공성과 사업성을 함께 높인다."
  )
]

let renderer = Renderer()
for content in contents {
  renderer.beginFormPage()
  renderer.drawText(content.background, in: backgroundBox, lineHeight: 14.0)
  renderer.drawText(content.overview, in: overviewBox, lineHeight: 14.0)
  renderer.drawText(content.data, in: dataBox, lineHeight: 14.0)
  renderer.drawText(content.growth, in: growthBox, lineHeight: 14.0)
  renderer.endPage()
}
renderer.save(to: outputPath)
