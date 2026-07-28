import AppKit
import CoreGraphics
import Foundation
import PDFKit

let originalFormPath = "/Users/josephuk77/Downloads/(양식1)『2026 관광데이터 활용 공모전』 제안서_팀명(대표자명).pdf"
let outputPath = CommandLine.arguments.count > 1
  ? CommandLine.arguments[1]
  : "docs/proposals/timing-jeju-form-preserving-5page.pdf"

let page = CGRect(x: 0, y: 0, width: 595, height: 842)
let margin: CGFloat = 50
let contentWidth = page.width - margin * 2

let bodyFont = NSFont(name: "AppleSDGothicNeo-Regular", size: 12) ?? NSFont.systemFont(ofSize: 12)
let bodyBold = NSFont(name: "AppleSDGothicNeo-Bold", size: 12) ?? NSFont.boldSystemFont(ofSize: 12)
let tableFont = NSFont(name: "AppleSDGothicNeo-Regular", size: 10.4) ?? NSFont.systemFont(ofSize: 10.4)
let tableBold = NSFont(name: "AppleSDGothicNeo-Bold", size: 10.4) ?? NSFont.boldSystemFont(ofSize: 10.4)
let smallFont = NSFont(name: "AppleSDGothicNeo-Regular", size: 8.2) ?? NSFont.systemFont(ofSize: 8.2)
let smallBold = NSFont(name: "AppleSDGothicNeo-Bold", size: 8.2) ?? NSFont.boldSystemFont(ofSize: 8.2)
let h1Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 18) ?? NSFont.boldSystemFont(ofSize: 18)
let h2Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 15) ?? NSFont.boldSystemFont(ofSize: 15)
let h3Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 13) ?? NSFont.boldSystemFont(ofSize: 13)

let dark = NSColor(calibratedWhite: 0.08, alpha: 1)
let blue = NSColor(calibratedRed: 0.02, green: 0.22, blue: 0.45, alpha: 1)
let lightBlue = NSColor(calibratedRed: 0.91, green: 0.95, blue: 0.99, alpha: 1)
let lineColor = NSColor(calibratedWhite: 0.45, alpha: 1)

final class Renderer {
  let ctx: CGContext
  let data: NSMutableData
  var pageNo = 0

  init() {
    data = NSMutableData()
    var box = page
    let consumer = CGDataConsumer(data: data as CFMutableData)!
    ctx = CGContext(consumer: consumer, mediaBox: &box, nil)!
  }

  func save(to path: String) {
    ctx.closePDF()
    data.write(toFile: path, atomically: true)
  }

  func beginBlankPage(title: String) {
    pageNo += 1
    ctx.beginPDFPage(nil)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
    ctx.saveGState()
    ctx.setFillColor(NSColor.white.cgColor)
    ctx.fill(page)
    ctx.restoreGState()
    drawTitleBar(title: title)
  }

  func beginOriginalFormPage() {
    pageNo += 1
    ctx.beginPDFPage(nil)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
    ctx.saveGState()
    ctx.setFillColor(NSColor.white.cgColor)
    ctx.fill(page)
    ctx.restoreGState()

    if let doc = PDFDocument(url: URL(fileURLWithPath: originalFormPath)),
       let formPage = doc.page(at: 0) {
      formPage.draw(with: .mediaBox, to: ctx)
    }
  }

  func endPage(showFooter: Bool = true) {
    if showFooter {
      drawRule(top: 809)
      drawText("타이밍제주 | 원본 양식 항목 및 순서를 유지한 작성본", x: margin, top: 816, width: contentWidth - 50, font: tableFont, color: NSColor.darkGray, lineHeight: 10)
      drawText("\(pageNo) / 5", x: page.width - margin - 42, top: 816, width: 42, font: tableFont, color: NSColor.darkGray, align: .right, lineHeight: 10)
    }
    NSGraphicsContext.restoreGraphicsState()
    ctx.endPDFPage()
  }

  func rect(_ x: CGFloat, _ top: CGFloat, _ width: CGFloat, _ height: CGFloat) -> CGRect {
    CGRect(x: x, y: page.height - top - height, width: width, height: height)
  }

  func drawTitleBar(title: String) {
    drawText("『2026 관광데이터 활용 공모전』 - ① 웹·앱 개발 부문 제안서", x: margin, top: 24, width: contentWidth, font: h1Font, color: dark, align: .center, lineHeight: 21)
    drawRule(top: 51)
    drawText(title, x: margin, top: 61, width: contentWidth, font: h2Font, color: blue, lineHeight: 18)
  }

  func drawRule(top: CGFloat) {
    ctx.saveGState()
    ctx.setStrokeColor(lineColor.cgColor)
    ctx.setLineWidth(0.6)
    ctx.move(to: CGPoint(x: margin, y: page.height - top))
    ctx.addLine(to: CGPoint(x: page.width - margin, y: page.height - top))
    ctx.strokePath()
    ctx.restoreGState()
  }

  func textAttrs(font: NSFont, color: NSColor, align: NSTextAlignment = .left, lineHeight: CGFloat) -> [NSAttributedString.Key: Any] {
    let style = NSMutableParagraphStyle()
    style.alignment = align
    style.minimumLineHeight = lineHeight
    style.maximumLineHeight = lineHeight
    style.lineBreakMode = .byWordWrapping
    return [.font: font, .foregroundColor: color, .paragraphStyle: style]
  }

  @discardableResult
  func drawText(_ text: String, x: CGFloat, top: CGFloat, width: CGFloat, font: NSFont = bodyFont, color: NSColor = dark, align: NSTextAlignment = .left, lineHeight: CGFloat = 15.2, maxHeight: CGFloat = 1_000) -> CGFloat {
    let attributed = NSAttributedString(string: text, attributes: textAttrs(font: font, color: color, align: align, lineHeight: lineHeight))
    let bounds = attributed.boundingRect(with: CGSize(width: width, height: maxHeight), options: [.usesLineFragmentOrigin, .usesFontLeading])
    let height = min(ceil(bounds.height) + 2, maxHeight)
    attributed.draw(with: rect(x, top, width, height), options: [.usesLineFragmentOrigin, .usesFontLeading])
    return top + height + 5
  }

  @discardableResult
  func heading(_ text: String, top: CGFloat) -> CGFloat {
    let next = drawText(text, x: margin, top: top, width: contentWidth, font: h3Font, color: blue, lineHeight: 16)
    ctx.saveGState()
    ctx.setFillColor(blue.cgColor)
    ctx.fill(CGRect(x: margin - 8, y: page.height - top - 1, width: 4, height: 15))
    ctx.restoreGState()
    return next + 2
  }

  func coverArea(x: CGFloat, top: CGFloat, width: CGFloat, height: CGFloat) {
    ctx.saveGState()
    ctx.setFillColor(NSColor.white.cgColor)
    ctx.fill(rect(x, top, width, height))
    ctx.restoreGState()
  }

  func box(_ text: String, x: CGFloat, top: CGFloat, width: CGFloat, height: CGFloat, font: NSFont = bodyBold) {
    ctx.saveGState()
    ctx.setFillColor(lightBlue.cgColor)
    ctx.fill(rect(x, top, width, height))
    ctx.setStrokeColor(lineColor.cgColor)
    ctx.stroke(rect(x, top, width, height))
    ctx.restoreGState()
    _ = drawText(text, x: x + 8, top: top + 7, width: width - 16, font: font, color: blue, align: .center, lineHeight: 14, maxHeight: height - 10)
  }

  @discardableResult
  func table(headers: [String], rows: [[String]], x: CGFloat, top: CGFloat, widths: [CGFloat], font: NSFont = tableFont, headerFont: NSFont = tableBold, lineHeight: CGFloat = 12.0) -> CGFloat {
    var y = top
    let allRows = [headers] + rows
    for (rowIndex, row) in allRows.enumerated() {
      let rowFont = rowIndex == 0 ? headerFont : font
      let isHeader = rowIndex == 0
      var rowHeight: CGFloat = 22
      for (idx, cell) in row.enumerated() {
        let attr = NSAttributedString(string: cell, attributes: textAttrs(font: rowFont, color: dark, align: isHeader ? .center : .left, lineHeight: lineHeight))
        let bounds = attr.boundingRect(with: CGSize(width: widths[idx] - 8, height: 220), options: [.usesLineFragmentOrigin, .usesFontLeading])
        rowHeight = max(rowHeight, ceil(bounds.height) + 9)
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
        _ = drawText(cell, x: xCursor + 4, top: y + 4, width: widths[idx] - 8, font: rowFont, color: dark, align: isHeader ? .center : .left, lineHeight: lineHeight, maxHeight: rowHeight - 6)
        xCursor += widths[idx]
      }
      y += rowHeight
    }
    return y + 7
  }
}

let r = Renderer()

// Page 1: original official PDF form preserved, filled in its fields.
r.beginOriginalFormPage()
r.coverArea(x: 62, top: 248, width: 470, height: 62)
_ = r.drawText("1. 서비스 기획 배경: 제주는 관광지가 넓게 분산되어 있어 렌터카 없이 이동하는 관광객에게 일정 운영 난이도가 높다. 2024년 제주 방문 관광객은 약 1,378만 명, 비짓제주 2024.1.1~11.30 누적 방문자는 531만 6,274명으로 온라인 관광정보 수요는 크지만, 버스 시간까지 고려한 일정 운영 서비스는 부족하다.", x: 65, top: 250, width: 465, font: smallFont, lineHeight: 10.1, maxHeight: 32)
_ = r.drawText("2. 서비스 필요성: POC 기준 제주공항→함덕→월정리→성산 일정은 이동 자체는 가능했지만, 버스를 놓치면 구간별 약 40~48분 추가 대기 위험이 발생했다. 타이밍제주는 출발 권장 시각, 일정 안전도, 주변 추천, 지연 복구안을 제공해 하루 일정 붕괴를 줄인다.", x: 65, top: 282, width: 465, font: smallFont, lineHeight: 10.1, maxHeight: 28)
r.coverArea(x: 62, top: 397, width: 470, height: 80)
_ = r.drawText("1. 기획 서비스 소개: TourAPI 관광정보와 제주 버스 시간표를 결합해, 렌터카 없이 제주를 여행하는 뚜벅이의 일정 실패를 줄이는 제주 특화 웹·앱 서비스.", x: 65, top: 399, width: 465, font: smallFont, lineHeight: 10.1, maxHeight: 18)
_ = r.drawText("2. 주요 기능: TourAPI 관광지 매칭, 정류장 후보 탐색, 시간표 기반 출발 권장 시각 계산, 일정 안전도 점수, 라이브 타임라인, 버스 놓침 시 주변 추천, 일정 지연 시 복구안 제공.", x: 65, top: 418, width: 465, font: smallFont, lineHeight: 10.1, maxHeight: 18)
_ = r.drawText("3. 차별성: 지도 앱은 구간 경로를 알려주지만 타이밍제주는 하루 전체 일정의 버스 타이밍을 관리한다. AI는 버스 시간을 생성하지 않고 서버 계산 결과만 설명한다.", x: 65, top: 437, width: 465, font: smallFont, lineHeight: 10.1, maxHeight: 18)
_ = r.drawText("4. 지역 특화: MVP는 제주공항·함덕·월정리·성산일출봉·섭지코지 등 제주 동쪽 코리도어에 집중해 지역특화 가점 및 제주 RTO 특별상 취지에 대응한다.", x: 65, top: 456, width: 465, font: smallFont, lineHeight: 10.1, maxHeight: 18)
r.coverArea(x: 62, top: 542, width: 470, height: 48)
_ = r.drawText("1. 활용 예정 한국관광공사 OpenAPI: 한국관광공사 국문 관광정보 서비스 TourAPI. 키워드검색, 위치기반 관광정보, 지역기반 관광정보, 이미지정보, 공통·소개정보, 행사정보, 숙박정보 활용.", x: 65, top: 544, width: 465, font: smallFont, lineHeight: 10.1, maxHeight: 22)
_ = r.drawText("2. 데이터 활용 방식: TourAPI로 장소명 매칭·관광지 좌표·이미지·소개·주변 후보를 확보하고, 제주 정류소/노선/시간표 데이터로 실행 가능성과 대기 위험을 계산한다.", x: 65, top: 567, width: 465, font: smallFont, lineHeight: 10.1, maxHeight: 20)
r.coverArea(x: 62, top: 659, width: 470, height: 42)
_ = r.drawText("1. 개발 서비스 향후 발전방향: 공모전 기간에는 제주 동쪽 코리도어 MVP를 완성하고, 이후 제주 전역 확장, 실시간 보정, 다국어 안내, RTO 대시보드로 발전시킨다. 기대효과는 버스 대기 시간 감소, 일정 실패 감소, TourAPI 활용도 증대, 지역관광 정책 데이터 기반 마련이다.", x: 65, top: 661, width: 465, font: smallFont, lineHeight: 10.1, maxHeight: 38)
r.endPage(showFooter: false)

// Page 2
r.beginBlankPage(title: "1) 서비스 기획배경 및 필요성 - 상세 작성")
var y: CGFloat = 82
y = r.heading("1. 서비스 기획 배경", top: y)
y = r.drawText("제주도는 국내 대표 관광지이지만, 렌터카 없이 이동하는 관광객에게는 일정 운영 난이도가 높은 지역이다. 제주 관광지는 동서남북으로 넓게 분산되어 있고, 관광지와 버스 정류장 사이의 도보 이동, 버스 배차 간격, 환승 가능 여부에 따라 하루 일정이 크게 달라진다. 특히 뚜벅이 여행자는 관광지를 고르는 것보다 ‘그 일정을 실제로 버스 시간에 맞춰 실행할 수 있는지’를 판단하는 데 어려움을 겪는다.", x: margin, top: y, width: contentWidth)
y = r.drawText("제주 관광 수요는 충분히 크다. 2024년 제주 방문 관광객은 약 1,378만 명 규모로 집계되었고, 제주관광공사에 따르면 제주 공식 여행 정보 플랫폼 비짓제주는 2024년 1월 1일부터 11월 30일까지 누적 방문자 수 531만 6,274명을 기록했다. 그러나 관광정보 탐색 수요가 높음에도, 렌터카 없이 이동하는 여행자가 버스 시간까지 고려해 하루 일정을 운영하도록 돕는 서비스는 부족하다.", x: margin, top: y, width: contentWidth)
r.box("핵심 문제: 관광지 정보 부족이 아니라 ‘이동 타이밍 실패’", x: margin, top: y + 2, width: contentWidth, height: 35)
y += 43
y = r.table(
  headers: ["구분", "현재 여행자가 직접 판단", "타이밍제주 제공"],
  rows: [
    ["관광지 선택", "장소명 검색, 정보 확인", "TourAPI 기반 관광지 후보 매칭"],
    ["정류장 확인", "지도에서 주변 정류장 직접 탐색", "관광지 주변 정류장 후보 자동 제시"],
    ["출발 시각", "버스 시간표와 도보시간 직접 계산", "시간표 기준 출발 권장 시각 계산"],
    ["일정 지연", "어떤 일정을 줄일지 직접 판단", "필수 일정 유지, 선택 일정 조정안 제공"],
    ["남는 시간", "주변 장소를 임의 검색", "남은 시간 안에 가능한 TourAPI 후보 추천"],
  ],
  x: margin,
  top: y,
  widths: [82, 205, 208]
)
y = r.heading("2. 서비스 필요성", top: y)
y = r.drawText("POC 시나리오에서 ‘제주공항 → 함덕해수욕장 → 월정리해변 → 성산일출봉’ 일정은 이동 자체는 가능했지만, 함덕 이후 구간부터 버스를 놓칠 경우 약 42~48분의 추가 대기 위험이 발생했다. 즉 겉보기에는 자연스러운 제주 동쪽 코스라도, 버스 시간까지 반영하면 일정 안전도가 낮아질 수 있다.", x: margin, top: y, width: contentWidth)
_ = r.table(
  headers: ["제공 정보", "사용자 가치"],
  rows: [
    ["관광지별 가까운 정류장 후보", "정류장 검색 시간 감소"],
    ["시간표 기준 출발 권장 시각", "버스 놓침 위험 감소"],
    ["다음 버스를 놓쳤을 때 추가 대기 위험", "일정 위험 사전 인지"],
    ["일정 안전도 점수", "무리한 일정 여부를 한눈에 판단"],
    ["남는 시간 안에 가능한 주변 관광지 추천", "대기 시간을 관광 경험으로 전환"],
    ["일정 지연 시 대체 일정 및 체류시간 조정안", "하루 일정 붕괴 방지"],
  ],
  x: margin,
  top: y,
  widths: [238, 257]
)
r.endPage()

// Page 3
r.beginBlankPage(title: "2) 서비스 개요 - 상세 작성")
y = 82
y = r.heading("1. 기획 서비스 소개(한 줄 작성)", top: y)
r.box("타이밍제주는 TourAPI 관광정보와 제주 버스 시간표를 결합해, 렌터카 없이 제주를 여행하는 뚜벅이의 일정 실패를 줄이는 제주 특화 웹·앱 서비스이다.", x: margin, top: y, width: contentWidth, height: 45)
y += 54
y = r.heading("2. 기획 서비스 주요 기능", top: y)
y = r.drawText("① TourAPI 기반 관광지 매칭: 자연어로 입력한 장소명을 한국관광공사 TourAPI 키워드 검색과 관광지 정보로 실제 관광지 후보와 매칭한다. 관광지명, 주소, 좌표, 이미지, 소개 정보를 일정 카드에 표시하고, 장소명이 애매한 경우 후보를 제시한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("② 버스 시간표 기반 일정 안전도 계산: 각 관광지 좌표와 제주 버스 정류소 정보를 비교해 가까운 정류장 후보를 찾고, 시간표 데이터를 기준으로 다음 목적지까지 이동 가능한 버스 후보를 계산한다.", x: margin, top: y, width: contentWidth)
y = r.table(
  headers: ["구간", "출발 권장", "후보 버스", "놓쳤을 때 위험", "상태"],
  rows: [
    ["제주공항 → 함덕해수욕장", "10:00", "10:15", "약 40분 대기", "주의"],
    ["함덕해수욕장 → 월정리해변", "12:10", "12:37", "약 42분 대기", "위험"],
    ["월정리해변 → 성산일출봉", "14:06", "14:58", "약 48분 대기", "위험"],
  ],
  x: margin,
  top: y,
  widths: [155, 74, 74, 130, 62]
)
y = r.table(
  headers: ["점수 항목", "점수", "의미"],
  rows: [
    ["경로 성립 점수", "100/100", "모든 구간에 이동 후보가 있음"],
    ["일정 안전도 점수", "38/100", "버스를 놓쳤을 때 대기 위험이 큼"],
    ["종합 실현 가능성 점수", "81/100", "갈 수는 있지만 일정 조정 권장"],
  ],
  x: margin,
  top: y,
  widths: [145, 84, 266]
)
y = r.drawText("③ 버스 놓침 방지 라이브 타임라인: 현재 시각 기준으로 초록·노랑·빨강 상태를 표시한다. ④ 남는 시간 및 버스 놓침 상황의 주변 추천: TourAPI 위치기반 관광정보를 활용해 남은 시간 안에 가능한 주변 관광지, 카페, 포토스팟을 추천한다. ⑤ 일정 지연 시 복구안 제공: 필수 방문지는 유지하고 선택 관광지를 줄이거나 제외하는 복구안을 제시한다.", x: margin, top: y, width: contentWidth)
y = r.heading("3. 서비스 차별성", top: y)
y = r.table(
  headers: ["구분", "기존 서비스", "한계", "타이밍제주"],
  rows: [
    ["지도 앱", "A에서 B까지 경로 안내", "하루 전체 일정 위험 판단 어려움", "일정 전체의 버스 타이밍 관리"],
    ["관광 추천 앱", "가볼 만한 장소 추천", "실제 이동 가능 시간 반영 부족", "남은 시간 안에 가능한 장소만 추천"],
    ["AI 여행 플래너", "그럴듯한 일정 생성", "버스 시간 오류 가능성", "TourAPI와 시간표 기반 위험도 검증"],
  ],
  x: margin,
  top: y,
  widths: [70, 125, 145, 155]
)
_ = r.drawText("OpenAI API를 사용하더라도 버스 시간이나 경로 가능 여부를 AI가 임의로 생성하지 않는다. 버스 시간, 출발 권장 시각, 위험도는 서버의 계산 엔진이 담당하고, OpenAI는 자연어 일정 분석과 설명 보조 역할을 담당한다.", x: margin, top: y, width: contentWidth)
r.endPage()

// Page 4
r.beginBlankPage(title: "2) 서비스 개요 - 지역 특화 / 3) 데이터 활용 방안")
y = 82
y = r.heading("4. (지역 특화 서비스의 경우 작성 필수) 서비스 내 지역 특화 관련 사항", top: y)
y = r.drawText("타이밍제주는 전국 범용 여행 서비스가 아니라 제주 지역 특화 서비스다. 제주 관광은 관광지 간 거리가 길고, 동서남북 관광권이 넓게 나뉘며, 렌터카 여부에 따라 여행 경험이 크게 달라진다. 특히 뚜벅이 관광객은 버스 배차와 정류장 위치를 고려하지 않으면 일정 실패 가능성이 높다.", x: margin, top: y, width: contentWidth)
y = r.drawText("공모전 MVP 범위는 제주 동쪽 대표 관광 코리도어다. 주요 대상지는 제주공항, 함덕해수욕장, 월정리해변, 성산일출봉, 섭지코지, 성산·서귀포 숙소권이다.", x: margin, top: y, width: contentWidth)
y = r.table(
  headers: ["제주 특화 요소", "반영 방식", "심사 관점"],
  rows: [
    ["관광지 간 거리와 분산성", "하루 전체 일정 기준 위험도 계산", "제주 문제의 구체성"],
    ["버스 배차 간격", "놓쳤을 때 추가 대기 시간 표시", "실용성"],
    ["정류장-관광지 도보 이동", "정류장 후보와 도보 시간 반영", "편의성"],
    ["동쪽 관광 코리도어", "공모전 MVP 범위로 우선 구현", "완성도"],
    ["지역특화 서비스 가점", "제주 단일 지역 문제에 집중", "가점 및 RTO 특별상"],
  ],
  x: margin,
  top: y,
  widths: [140, 215, 140]
)
y = r.heading("1. 활용 예정 한국관광공사 OpenAPI", top: y)
y = r.drawText("활용 예정 한국관광공사 OpenAPI는 한국관광공사 국문 관광정보 서비스 TourAPI이다. 공공데이터포털 기준 해당 API는 REST 방식, JSON+XML 포맷을 제공하며, 지역기반 관광정보, 위치기반 관광정보, 키워드검색, 행사정보, 숙박정보, 공통정보, 소개정보, 이미지정보 등 15종 약 26만 건의 국내 관광정보를 제공한다.", x: margin, top: y, width: contentWidth)
y = r.heading("2. 데이터 활용 방식", top: y)
_ = r.table(
  headers: ["데이터", "활용 방식", "서비스 기능"],
  rows: [
    ["TourAPI 키워드 검색", "입력 장소명을 관광지 후보로 매칭", "일정 생성"],
    ["TourAPI 위치기반 관광정보", "현재 위치 주변 관광지·음식점 후보 조회", "남는 시간 추천"],
    ["TourAPI 이미지정보", "추천 카드와 일정 카드에 이미지 표시", "사용자 선택 지원"],
    ["TourAPI 공통·소개정보", "관광지 설명·주소·좌표·카테고리 표시", "관광지 상세"],
    ["제주 버스 시간표 정보", "출발 권장 시각·대기 위험 계산", "일정 운영"],
    ["OpenAI API", "자연어 일정 파싱·위험 설명", "보조 설명"],
  ],
  x: margin,
  top: y,
  widths: [145, 245, 105]
)
r.endPage()

// Page 5
r.beginBlankPage(title: "4) 서비스 발전 방향")
y = 82
y = r.heading("1. 개발 서비스 향후 발전방향", top: y)
y = r.drawText("1단계는 공모전 MVP 완성이다. 공모전 개발 기간에는 제주 동쪽 코리도어 중심으로 핵심 기능을 완성한다. 주요 목표는 TourAPI 장소 매칭, 시간표 기반 출발 권장 시각 계산, 일정 안전도 표시, 버스 놓침 시 주변 추천, 일정 지연 시 복구안 제공이다.", x: margin, top: y, width: contentWidth)
y = r.drawText("2단계는 제주 전역 확장이다. MVP에서 검증한 구조를 바탕으로 제주 전역의 정류장, 노선, 시간표 데이터를 확대 적용한다. 노선별 정류장 순서와 시간표 정합성을 검증한 뒤, 더 많은 지역의 뚜벅이 여행 일정을 지원한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("3단계는 실시간 보정 및 다국어 안내다. 정류장별 도착예정 정보 또는 버스 위치 데이터 활용 가능성이 확보되면 시간표 기반 안내에 실시간 보정을 추가한다. 또한 외국인 관광객을 위해 한국어 일정 안내를 영어·중국어·일본어 등으로 제공한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("4단계는 지역관광 운영 데이터로의 확장이다. 향후에는 RTO나 지자체가 뚜벅이 관광객의 이동 불편 구간을 파악하고, 관광지 분산 방문과 대중교통 기반 관광 활성화에 참고할 수 있는 대시보드로 발전시킬 수 있다.", x: margin, top: y, width: contentWidth)
y = r.table(
  headers: ["발전 단계", "주요 기능", "기대효과"],
  rows: [
    ["MVP", "동쪽 코리도어 일정 운영", "기능심사 시연 가능"],
    ["제주 전역 확장", "전체 정류장·노선·시간표 확대", "더 많은 뚜벅이 일정 지원"],
    ["실시간 보정", "버스 위치/도착 정보 반영", "안내 정확도 향상"],
    ["다국어 안내", "영어·중국어·일본어 안내", "외국인 관광객 접근성 향상"],
    ["RTO 대시보드", "지연·대기 위험 구간 분석", "지역관광 정책 활용"],
  ],
  x: margin,
  top: y,
  widths: [105, 220, 170]
)
y = r.heading("정량 기대효과 및 심사기준 대응", top: y)
y = r.table(
  headers: ["항목", "수치/목표", "의미"],
  rows: [
    ["제주 관광 수요", "2024년 약 1,378만 명", "충분한 대상 시장"],
    ["비짓제주 온라인 수요", "2024.1.1~11.30 누적 531만 6,274명", "온라인 관광정보 활용 수요"],
    ["TourAPI 규모", "15종 약 26만 건", "관광지 매칭·주변 추천 데이터 기반"],
    ["POC 대기 위험", "약 42~48분", "버스 놓침 문제의 구체성"],
  ],
  x: margin,
  top: y,
  widths: [135, 180, 180]
)
_ = r.table(
  headers: ["심사항목", "대응 전략"],
  rows: [
    ["서비스 기획력", "제주 뚜벅이의 이동 타이밍 실패 문제를 구체화"],
    ["서비스 완성도", "동쪽 코리도어 MVP로 5월~9월 개발 기간 내 기능심사 대응"],
    ["데이터 활용 적절성", "TourAPI를 장소 매칭·주변 추천의 핵심 데이터로 사용"],
    ["서비스 발전성", "제주 전역·실시간 보정·RTO 대시보드로 확장"],
  ],
  x: margin,
  top: y,
  widths: [135, 360]
)
r.endPage()

r.save(to: outputPath)
