import AppKit
import CoreGraphics
import Foundation
import PDFKit

let blankFormPath = "/Users/josephuk77/Downloads/(양식1)『2026 관광데이터 활용 공모전』 제안서_팀명(대표자명) (1).pdf"
let outputPath = CommandLine.arguments.count > 1
  ? CommandLine.arguments[1]
  : "docs/proposals/timing-jeju-blank-form-12pt-5page.pdf"

let page = CGRect(x: 0, y: 0, width: 595, height: 842)
let margin: CGFloat = 50
let contentWidth = page.width - margin * 2

let bodyFont = NSFont(name: "AppleSDGothicNeo-Regular", size: 12) ?? NSFont.systemFont(ofSize: 12)
let bodyBold = NSFont(name: "AppleSDGothicNeo-Bold", size: 12) ?? NSFont.boldSystemFont(ofSize: 12)
let h1Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 18) ?? NSFont.boldSystemFont(ofSize: 18)
let h2Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 15) ?? NSFont.boldSystemFont(ofSize: 15)
let h3Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 13) ?? NSFont.boldSystemFont(ofSize: 13)

let dark = NSColor(calibratedWhite: 0.08, alpha: 1)
let blue = NSColor(calibratedRed: 0.02, green: 0.22, blue: 0.45, alpha: 1)
let lightBlue = NSColor(calibratedRed: 0.92, green: 0.96, blue: 1.0, alpha: 1)
let lineColor = NSColor(calibratedWhite: 0.48, alpha: 1)

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

  func beginPDFPage() {
    pageNo += 1
    ctx.beginPDFPage(nil)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
    ctx.saveGState()
    ctx.setFillColor(NSColor.white.cgColor)
    ctx.fill(page)
    ctx.restoreGState()
  }

  func beginFormPage() {
    beginPDFPage()
    if let doc = PDFDocument(url: URL(fileURLWithPath: blankFormPath)),
       let formPage = doc.page(at: 0) {
      formPage.draw(with: .mediaBox, to: ctx)
    }
  }

  func beginContinuationPage(title: String) {
    beginPDFPage()
    drawText("『2026 관광데이터 활용 공모전』 - ① 웹·앱 개발 부문 제안서", x: margin, top: 24, width: contentWidth, font: h1Font, align: .center, lineHeight: 21)
    drawRule(top: 52)
    drawText(title, x: margin, top: 62, width: contentWidth, font: h2Font, color: blue, lineHeight: 18)
  }

  func endPage() {
    drawRule(top: 806)
    drawText("타이밍제주 | 12pt 이상 작성본", x: margin, top: 814, width: contentWidth - 60, font: bodyFont, color: NSColor.darkGray, lineHeight: 13)
    drawText("\(pageNo) / 5", x: page.width - margin - 50, top: 814, width: 50, font: bodyFont, color: NSColor.darkGray, align: .right, lineHeight: 13)
    NSGraphicsContext.restoreGraphicsState()
    ctx.endPDFPage()
  }

  func rect(_ x: CGFloat, _ top: CGFloat, _ width: CGFloat, _ height: CGFloat) -> CGRect {
    CGRect(x: x, y: page.height - top - height, width: width, height: height)
  }

  func attrs(font: NSFont, color: NSColor, align: NSTextAlignment, lineHeight: CGFloat) -> [NSAttributedString.Key: Any] {
    let style = NSMutableParagraphStyle()
    style.alignment = align
    style.minimumLineHeight = lineHeight
    style.maximumLineHeight = lineHeight
    style.lineBreakMode = .byWordWrapping
    return [.font: font, .foregroundColor: color, .paragraphStyle: style]
  }

  @discardableResult
  func drawText(_ text: String, x: CGFloat, top: CGFloat, width: CGFloat, font: NSFont = bodyFont, color: NSColor = dark, align: NSTextAlignment = .left, lineHeight: CGFloat = 15.0, maxHeight: CGFloat = 1_000) -> CGFloat {
    let attributed = NSAttributedString(string: text, attributes: attrs(font: font, color: color, align: align, lineHeight: lineHeight))
    let bounds = attributed.boundingRect(with: CGSize(width: width, height: maxHeight), options: [.usesLineFragmentOrigin, .usesFontLeading])
    let height = min(ceil(bounds.height) + 2, maxHeight)
    attributed.draw(with: rect(x, top, width, height), options: [.usesLineFragmentOrigin, .usesFontLeading])
    return top + height + 5
  }

  func drawRule(top: CGFloat) {
    ctx.saveGState()
    ctx.setStrokeColor(lineColor.cgColor)
    ctx.setLineWidth(0.65)
    ctx.move(to: CGPoint(x: margin, y: page.height - top))
    ctx.addLine(to: CGPoint(x: page.width - margin, y: page.height - top))
    ctx.strokePath()
    ctx.restoreGState()
  }

  @discardableResult
  func heading(_ text: String, top: CGFloat) -> CGFloat {
    ctx.saveGState()
    ctx.setFillColor(blue.cgColor)
    ctx.fill(CGRect(x: margin - 8, y: page.height - top - 1, width: 4, height: 16))
    ctx.restoreGState()
    return drawText(text, x: margin, top: top, width: contentWidth, font: h3Font, color: blue, lineHeight: 16) + 1
  }

  func callout(_ text: String, top: CGFloat, height: CGFloat = 38) -> CGFloat {
    ctx.saveGState()
    ctx.setFillColor(lightBlue.cgColor)
    ctx.fill(rect(margin, top, contentWidth, height))
    ctx.setStrokeColor(lineColor.cgColor)
    ctx.stroke(rect(margin, top, contentWidth, height))
    ctx.restoreGState()
    _ = drawText(text, x: margin + 8, top: top + 7, width: contentWidth - 16, font: bodyBold, color: blue, align: .center, lineHeight: 14.5, maxHeight: height - 10)
    return top + height + 8
  }

  @discardableResult
  func table(headers: [String], rows: [[String]], x: CGFloat, top: CGFloat, widths: [CGFloat]) -> CGFloat {
    var y = top
    let allRows = [headers] + rows
    for (rowIndex, row) in allRows.enumerated() {
      let isHeader = rowIndex == 0
      let font = isHeader ? bodyBold : bodyFont
      var rowHeight: CGFloat = 27
      for (idx, cell) in row.enumerated() {
        let attributed = NSAttributedString(string: cell, attributes: attrs(font: font, color: dark, align: isHeader ? .center : .left, lineHeight: 13.8))
        let bounds = attributed.boundingRect(with: CGSize(width: widths[idx] - 8, height: 260), options: [.usesLineFragmentOrigin, .usesFontLeading])
        rowHeight = max(rowHeight, ceil(bounds.height) + 10)
      }
      var xCursor = x
      for (idx, cell) in row.enumerated() {
        let cellRect = rect(xCursor, y, widths[idx], rowHeight)
        ctx.saveGState()
        ctx.setFillColor((isHeader ? lightBlue : NSColor.white).cgColor)
        ctx.fill(cellRect)
        ctx.setStrokeColor(lineColor.cgColor)
        ctx.setLineWidth(0.45)
        ctx.stroke(cellRect)
        ctx.restoreGState()
        _ = drawText(cell, x: xCursor + 4, top: y + 5, width: widths[idx] - 8, font: font, align: isHeader ? .center : .left, lineHeight: 13.8, maxHeight: rowHeight - 7)
        xCursor += widths[idx]
      }
      y += rowHeight
    }
    return y + 8
  }
}

let r = Renderer()
var y: CGFloat = 0

// Page 1: use the user's second blank PDF as the preserved official form.
r.beginFormPage()
_ = r.drawText("1. 서비스 기획 배경: 제주 관광은 관광지 간 거리가 길고 버스 배차·환승·정류장 도보 이동이 일정 실패로 이어지기 쉽다.\n2. 서비스 필요성: TourAPI 관광정보와 제주 버스 시간표를 결합해 출발 권장 시각, 대기 위험, 대체 일정을 제공한다.", x: 61, top: 244, width: 475, font: bodyFont, lineHeight: 15.0, maxHeight: 64)
_ = r.drawText("1. 소개: 제주 뚜벅이의 버스 시간 기반 AI 일정 운영 서비스\n2. 주요 기능: 장소 매칭, 출발시각, 안전도, 주변추천, 재일정\n3. 차별성: 단일 경로가 아닌 하루 전체 버스 타이밍 관리\n4. 지역 특화: 제주 동쪽 코리도어 검증 후 전역 확장", x: 61, top: 383, width: 475, font: bodyFont, lineHeight: 15.0, maxHeight: 86)
_ = r.drawText("1. 활용 OpenAPI: 한국관광공사 국문관광정보 TourAPI\n2. 활용 방식: 관광지 좌표·이미지·소개 정보와 제주 정류장·노선·시간표를 결합해 실행 가능한 일정을 계산한다.", x: 61, top: 526, width: 475, font: bodyFont, lineHeight: 15.0, maxHeight: 56)
_ = r.drawText("1. 개발 서비스 향후 발전방향: 공모전 MVP는 제주 동쪽 일정 운영에 집중하고, 이후 제주 전역·실시간 보정·다국어 안내·RTO 대시보드로 확장한다.", x: 61, top: 646, width: 475, font: bodyFont, lineHeight: 15.0, maxHeight: 48)
NSGraphicsContext.restoreGraphicsState()
r.ctx.endPDFPage()

// Page 2
r.beginContinuationPage(title: "1) 서비스 기획배경 및 필요성")
y = 84
y = r.heading("1. 서비스 기획 배경", top: y)
y = r.drawText("제주도는 국내 대표 관광지이지만 렌터카 없이 이동하는 관광객에게는 일정 운영 난도가 높다. 관광지가 넓게 분산되어 있고, 관광지와 정류장 사이의 도보 이동, 버스 배차 간격, 환승 가능 여부가 하루 일정의 성패를 좌우한다. 기존 지도 앱은 A에서 B까지의 경로를 알려주지만, 여행자가 하루 전체 일정을 기준으로 언제 나와야 하는지와 버스를 놓쳤을 때 어떤 일정을 조정해야 하는지는 판단하기 어렵다.", x: margin, top: y, width: contentWidth)
y = r.drawText("시장 수요도 충분하다. 제주특별자치도 관광협회 통계 기준 2024년 제주 방문 관광객은 약 1,378만 명 규모이며, 제주관광공사 비짓제주는 2024년 1월 1일부터 11월 30일까지 누적 방문자 531만 6,274명을 기록했다. 즉 제주 관광정보를 온라인으로 탐색하는 수요는 크지만, 뚜벅이 관광객의 이동 타이밍 실패를 줄이는 전용 서비스는 아직 부족하다.", x: margin, top: y, width: contentWidth)
y = r.callout("핵심 문제: 관광지 정보 부족이 아니라 ‘버스 시간과 맞지 않는 일정 운영’", top: y)
y = r.table(
  headers: ["구분", "현재 여행자 행동", "타이밍제주 제공 가치"],
  rows: [
    ["관광지 선택", "블로그·지도에서 장소를 따로 검색", "TourAPI 기반 관광지 후보 매칭"],
    ["정류장 확인", "주변 정류장과 도보 시간을 직접 확인", "관광지 주변 정류장 후보 자동 제시"],
    ["출발 시각", "시간표와 도보시간을 직접 계산", "출발 권장 시각과 안전 버퍼 계산"],
    ["일정 지연", "어떤 일정을 줄일지 직접 판단", "필수 일정 유지, 선택 일정 조정안 제시"],
    ["남는 시간", "가까운 곳을 임의 검색", "남은 시간 안에 가능한 TourAPI 후보 추천"],
  ],
  x: margin,
  top: y,
  widths: [82, 205, 208]
)
y = r.heading("2. 서비스 필요성", top: y)
y = r.drawText("POC 시나리오에서 ‘제주공항 → 함덕해수욕장 → 월정리해변 → 성산일출봉’ 일정은 이동 자체는 가능했지만, 함덕 이후 구간부터 버스를 놓칠 경우 약 42~48분의 추가 대기 위험이 발생했다. 따라서 이 서비스는 단순 추천 앱이 아니라 버스 시간 때문에 일정이 무너지는 문제를 사전에 줄이는 일정 운영 도구가 되어야 한다.", x: margin, top: y, width: contentWidth)
y = r.table(
  headers: ["제공 정보", "사용자 가치"],
  rows: [
    ["가까운 정류장 후보", "정류장 검색과 도보 이동 판단 시간을 줄임"],
    ["시간표 기준 출발 권장 시각", "버스 놓침 위험을 사전에 낮춤"],
    ["놓쳤을 때 추가 대기 위험", "일정 포기·조정 판단을 빠르게 지원"],
    ["일정 안전도 점수", "무리한 일정 여부를 한눈에 확인"],
    ["남는 시간 주변 추천", "대기 시간을 관광 경험으로 전환"],
  ],
  x: margin,
  top: y,
  widths: [215, 280]
)
y = r.drawText("기획 배경에는 실제 사용 경험도 반영된다. 20살 제주 뚜벅이 여행 중 버스 배차 간격을 제대로 확인하지 못해 최대 1시간 가까이 기다린 경험이 있었고, 이 문제는 단순 불편이 아니라 다음 목적지 체류시간과 숙소 도착시간까지 밀리게 만드는 일정 실패 요인이다. 타이밍제주는 이 경험을 데이터 기반 서비스로 해결한다.", x: margin, top: y, width: contentWidth)
r.endPage()

// Page 3
r.beginContinuationPage(title: "2) 서비스 개요")
y = 84
y = r.heading("1. 기획 서비스 소개(한 줄 작성)", top: y)
y = r.callout("타이밍제주는 TourAPI 관광정보와 제주 버스 데이터를 결합해 렌터카 없이 제주를 여행하는 뚜벅이의 일정 실패를 줄이는 제주 특화 웹·앱 서비스이다.", top: y, height: 52)
y = r.heading("2. 기획 서비스 주요 기능", top: y)
y = r.drawText("① TourAPI 기반 관광지 매칭: 사용자가 자연어로 입력한 장소명을 한국관광공사 TourAPI 키워드 검색과 관광지 정보로 실제 관광지 후보와 매칭한다. 관광지명, 주소, 좌표, 대표 이미지, 소개 정보를 일정 카드에 표시하고 장소명이 애매하면 후보 선택 화면을 제공한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("② 버스 시간표 기반 일정 안전도 계산: 각 관광지 좌표와 제주 정류소 좌표를 비교해 가까운 정류장을 찾고, 시간표 데이터로 다음 목적지까지 이동 가능한 버스 후보를 계산한다. 점수는 구현 가능성 점수가 아니라 사용자가 입력한 일정이 버스 시간에 얼마나 안전한지를 보여주는 운영 점수다.", x: margin, top: y, width: contentWidth)
y = r.table(
  headers: ["구간", "권장 출발", "후보 버스", "놓쳤을 때 위험", "상태"],
  rows: [
    ["제주공항 → 함덕", "10:00", "10:15", "약 40분 대기", "주의"],
    ["함덕 → 월정리", "12:10", "12:37", "약 42분 대기", "위험"],
    ["월정리 → 성산", "14:06", "14:58", "약 48분 대기", "위험"],
  ],
  x: margin,
  top: y,
  widths: [145, 82, 82, 124, 62]
)
y = r.table(
  headers: ["기능", "구현 방식", "심사 시연 포인트"],
  rows: [
    ["라이브 타임라인", "현재 시각 기준 초록·노랑·빨강 상태 표시", "지금 더 머물러도 되는지 안내"],
    ["놓침 방지 알림", "정류장 도보시간+안전버퍼+버스시간 계산", "출발 준비·이동·위험 알림"],
    ["남는 시간 추천", "TourAPI 위치기반 후보 중 시간 내 가능 후보 필터링", "대기 시간을 관광 경험으로 전환"],
    ["AI 재일정", "필수 방문지는 유지하고 선택 일정을 조정", "지연 시 대체 일정 설명"],
  ],
  x: margin,
  top: y,
  widths: [118, 225, 152]
)
y = r.heading("3. 서비스 차별성", top: y)
y = r.table(
  headers: ["구분", "기존 서비스 한계", "타이밍제주 차별성"],
  rows: [
    ["지도 앱", "구간 경로 안내 중심", "하루 전체 일정의 버스 타이밍 관리"],
    ["관광 추천 앱", "가볼 만한 곳 추천 중심", "남은 시간 안에 실제 가능한 곳만 추천"],
    ["AI 여행 플래너", "그럴듯한 일정 생성 위험", "서버 계산 결과만 AI가 설명해 환각 방지"],
  ],
  x: margin,
  top: y,
  widths: [82, 200, 213]
)
y = r.drawText("일정 안전도는 구현 가능성 평가가 아니라 사용자의 여행 일정 자체에 부여하는 운영 점수다. 경로 성립 여부, 버스 대기 위험, 정류장 도보 시간, 환승 여유, 대체 노선 수를 합산해 0~100점으로 표시하고, 점수가 낮으면 AI가 체류시간 단축·목적지 순서 변경·주변 대체지 추천을 설명한다.", x: margin, top: y, width: contentWidth)
r.endPage()

// Page 4
r.beginContinuationPage(title: "2) 서비스 개요 - 지역 특화 / 3) 데이터 활용 방안")
y = 84
y = r.heading("4. (지역 특화 서비스의 경우 작성 필수) 서비스 내 지역 특화 관련 사항", top: y)
y = r.drawText("타이밍제주는 전국 범용 여행 서비스가 아니라 제주 지역 특화 서비스다. 제주 관광은 관광지 간 거리가 길고, 동서남북 관광권이 뚜렷하며, 렌터카 여부에 따라 여행 경험이 크게 달라진다. 특히 뚜벅이 관광객은 버스 배차와 정류장 위치를 고려하지 않으면 한 번의 지연이 다음 관광지 체류시간과 숙소 도착시간까지 연쇄적으로 영향을 준다.", x: margin, top: y, width: contentWidth)
y = r.drawText("공모전 MVP 범위는 제주 동쪽 대표 관광 코리도어다. 제주공항, 함덕해수욕장, 월정리해변, 성산일출봉, 섭지코지, 성산·서귀포 숙소권을 우선 대상으로 삼아 기능심사에서 실제 동선 기반 시연이 가능하도록 구현한다.", x: margin, top: y, width: contentWidth)
y = r.table(
  headers: ["제주 특화 요소", "서비스 반영 방식", "심사 관점"],
  rows: [
    ["관광지 분산성", "하루 전체 일정 기준 위험도 계산", "문제 구체성"],
    ["버스 배차 간격", "놓쳤을 때 추가 대기 시간 표시", "실용성"],
    ["정류장-관광지 도보 이동", "정류장 후보와 도보 시간 반영", "편의성"],
    ["동쪽 관광 코리도어", "MVP 범위로 우선 구현", "완성도"],
    ["지역특화 서비스 가점", "제주 단일 지역 문제에 집중", "RTO 특별상 대응"],
  ],
  x: margin,
  top: y,
  widths: [155, 210, 130]
)
y = r.heading("1. 활용 예정 한국관광공사 OpenAPI", top: y)
y = r.drawText("활용 예정 한국관광공사 OpenAPI는 한국관광공사 국문 관광정보 서비스 TourAPI이다. 공공데이터포털 기준 TourAPI는 REST 방식과 JSON·XML 포맷을 제공하며, 지역기반 관광정보, 위치기반 관광정보, 키워드검색, 행사정보, 숙박정보, 공통정보, 소개정보, 이미지정보 등 15종 약 26만 건의 국내 관광정보를 제공한다.", x: margin, top: y, width: contentWidth)
y = r.heading("2. 데이터 활용 방식", top: y)
y = r.table(
  headers: ["데이터", "활용 방식", "서비스 기능"],
  rows: [
    ["TourAPI 키워드검색", "입력 장소명을 관광지 후보로 매칭", "일정 생성"],
    ["TourAPI 위치기반", "현재 위치 주변 후보 조회", "남는 시간 추천"],
    ["TourAPI 이미지정보", "추천 카드에 이미지 표시", "사용자 선택 지원"],
    ["TourAPI 공통·소개", "주소·좌표·카테고리·설명 표시", "관광지 상세"],
    ["제주 버스 시간표", "출발 권장 시각·대기 위험 계산", "일정 운영"],
    ["OpenAI API", "자연어 일정 파싱과 위험 설명", "AI 일정 매니저"],
  ],
  x: margin,
  top: y,
  widths: [145, 235, 115]
)
y = r.drawText("정확도 원칙은 명확하다. OpenAI API가 버스 시간을 임의 생성하지 않으며, 시간·정류장·위험도는 서버 계산 엔진이 처리한다. AI는 사용자의 자연어 입력을 구조화하고 계산 결과를 이해하기 쉬운 문장으로 설명하는 역할에 한정한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("MVP에서는 실시간 도착 정보가 확보되지 않아도 시간표·정류소·노선 데이터만으로 출발 권장 시각과 놓쳤을 때의 추가 대기 위험을 계산할 수 있다. 이후 실시간 버스 위치 또는 도착 예정 정보가 확인되면 같은 구조에 보정값을 더해 정확도를 높인다.", x: margin, top: y, width: contentWidth)
r.endPage()

// Page 5
r.beginContinuationPage(title: "4) 서비스 발전 방향")
y = 84
y = r.heading("1. 개발 서비스 향후 발전방향", top: y)
y = r.drawText("1단계는 공모전 MVP 완성이다. 공모전 개발 기간에는 제주 동쪽 코리도어 중심으로 TourAPI 장소 매칭, 정류장 후보 탐색, 시간표 기반 출발 권장 시각 계산, 일정 안전도 표시, 버스 놓침 시 주변 추천, 일정 지연 시 복구안 제공을 완성한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("2단계는 제주 전역 확장이다. MVP에서 검증한 데이터 구조를 바탕으로 제주 전역의 정류장, 노선, 시간표 데이터를 확대 적용한다. 이때 노선별 정류장 순서와 시간표 정합성을 검증해 지역별로 안정적인 뚜벅이 일정을 지원한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("3단계는 실시간 보정 및 다국어 안내다. 정류장별 도착 예정 정보 또는 버스 위치 데이터 활용 가능성이 확보되면 시간표 기반 안내에 실시간 보정을 추가한다. 외국인 관광객을 위해 한국어 일정 안내를 영어·중국어·일본어로 제공한다.", x: margin, top: y, width: contentWidth)
y = r.table(
  headers: ["발전 단계", "주요 기능", "기대효과"],
  rows: [
    ["MVP", "동쪽 코리도어 일정 운영", "기능심사 시연 가능"],
    ["제주 전역 확장", "전체 정류장·노선·시간표 확대", "더 많은 뚜벅이 일정 지원"],
    ["실시간 보정", "버스 위치·도착 정보 반영", "안내 정확도 향상"],
    ["다국어 안내", "영어·중국어·일본어 안내", "외국인 접근성 향상"],
    ["RTO 대시보드", "대기 위험 구간 분석", "지역관광 정책 활용"],
  ],
  x: margin,
  top: y,
  widths: [115, 220, 160]
)
y = r.heading("정량 기대효과 및 심사기준 대응", top: y)
y = r.table(
  headers: ["항목", "수치/목표", "의미"],
  rows: [
    ["제주 관광 수요", "2024년 약 1,378만 명", "충분한 대상 시장"],
    ["비짓제주 수요", "2024.1.1~11.30 누적 531만 6,274명", "온라인 관광정보 활용 수요"],
    ["TourAPI 규모", "15종 약 26만 건", "관광지 매칭·주변 추천 기반"],
    ["POC 대기 위험", "약 42~48분", "문제의 구체성"],
  ],
  x: margin,
  top: y,
  widths: [130, 210, 155]
)
y = r.table(
  headers: ["심사항목", "대응 전략"],
  rows: [
    ["서비스 기획력", "제주 뚜벅이의 이동 타이밍 실패 문제를 구체화"],
    ["서비스 완성도", "동쪽 코리도어 MVP로 개발 기간 내 기능심사 대응"],
    ["데이터 활용 적절성", "TourAPI를 장소 매칭·주변 추천의 핵심 데이터로 사용"],
    ["서비스 발전성", "제주 전역·실시간 보정·RTO 대시보드로 확장"],
  ],
  x: margin,
  top: y,
  widths: [145, 350]
)
y = r.drawText("기대효과는 사용자와 지역 모두에 있다. 사용자는 렌터카 없이도 예측 가능한 제주 일정을 운영할 수 있고, 지역은 관광객이 특정 관광지에만 몰리지 않도록 주변 장소와 대체 동선을 추천받는 효과를 얻는다. 또한 공사 OpenAPI는 단순 정보 조회가 아니라 실제 여행 의사결정의 핵심 데이터로 활용된다.", x: margin, top: y, width: contentWidth)
r.endPage()

r.save(to: outputPath)
