import AppKit
import CoreGraphics
import Foundation

let outputPath = CommandLine.arguments.count > 1
  ? CommandLine.arguments[1]
  : "docs/proposals/timing-jeju-5page-proposal.pdf"

let page = CGRect(x: 0, y: 0, width: 595, height: 842)
let margin: CGFloat = 34
let contentWidth = page.width - margin * 2

let bodyFont = NSFont(name: "AppleSDGothicNeo-Regular", size: 10.7) ?? NSFont.systemFont(ofSize: 10.7)
let bodyBold = NSFont(name: "AppleSDGothicNeo-Bold", size: 10.9) ?? NSFont.boldSystemFont(ofSize: 10.9)
let smallFont = NSFont(name: "AppleSDGothicNeo-Regular", size: 9.2) ?? NSFont.systemFont(ofSize: 9.2)
let smallBold = NSFont(name: "AppleSDGothicNeo-Bold", size: 9.3) ?? NSFont.boldSystemFont(ofSize: 9.3)
let h1Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 17) ?? NSFont.boldSystemFont(ofSize: 17)
let h2Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 13.2) ?? NSFont.boldSystemFont(ofSize: 13.2)
let h3Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 11.4) ?? NSFont.boldSystemFont(ofSize: 11.4)

let dark = NSColor(calibratedWhite: 0.08, alpha: 1)
let blue = NSColor(calibratedRed: 0.05, green: 0.22, blue: 0.42, alpha: 1)
let lightBlue = NSColor(calibratedRed: 0.91, green: 0.95, blue: 0.99, alpha: 1)
let lineColor = NSColor(calibratedWhite: 0.45, alpha: 1)

final class Renderer {
  let ctx: CGContext
  let data: NSMutableData
  var pageNo = 0

  init(path: String) {
    data = NSMutableData()
    var box = page
    let consumer = CGDataConsumer(data: data as CFMutableData)!
    ctx = CGContext(consumer: consumer, mediaBox: &box, nil)!
  }

  func save(to path: String) {
    ctx.closePDF()
    data.write(toFile: path, atomically: true)
  }

  func beginPage(title: String) {
    pageNo += 1
    ctx.beginPDFPage(nil)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)

    ctx.saveGState()
    ctx.setFillColor(NSColor.white.cgColor)
    ctx.fill(page)
    ctx.restoreGState()

    drawText("『2026 관광데이터 활용 공모전』 ① 웹·앱 개발 부문 제안서", x: margin, top: 18, width: contentWidth, font: smallFont, color: NSColor.darkGray, align: .center, lineHeight: 11)
    drawRule(top: 35)
    drawText(title, x: margin, top: 43, width: contentWidth, font: h2Font, color: blue, align: .left, lineHeight: 15)
  }

  func endPage() {
    drawRule(top: 806)
    drawText("타이밍제주 | TourAPI 관광정보와 제주 버스 시간표를 결합한 제주 특화 일정 운영 서비스", x: margin, top: 813, width: contentWidth - 50, font: smallFont, color: NSColor.darkGray, align: .left, lineHeight: 10)
    drawText("\(pageNo) / 5", x: page.width - margin - 40, top: 813, width: 40, font: smallFont, color: NSColor.darkGray, align: .right, lineHeight: 10)
    NSGraphicsContext.restoreGraphicsState()
    ctx.endPDFPage()
  }

  func rect(_ x: CGFloat, _ top: CGFloat, _ width: CGFloat, _ height: CGFloat) -> CGRect {
    CGRect(x: x, y: page.height - top - height, width: width, height: height)
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
    return [
      .font: font,
      .foregroundColor: color,
      .paragraphStyle: style,
    ]
  }

  @discardableResult
  func drawText(_ text: String, x: CGFloat, top: CGFloat, width: CGFloat, font: NSFont = bodyFont, color: NSColor = dark, align: NSTextAlignment = .left, lineHeight: CGFloat = 13.7, maxHeight: CGFloat = 1_000) -> CGFloat {
    let attributed = NSAttributedString(string: text, attributes: textAttrs(font: font, color: color, align: align, lineHeight: lineHeight))
    let bounds = attributed.boundingRect(with: CGSize(width: width, height: maxHeight), options: [.usesLineFragmentOrigin, .usesFontLeading])
    let height = min(ceil(bounds.height) + 2, maxHeight)
    attributed.draw(with: rect(x, top, width, height), options: [.usesLineFragmentOrigin, .usesFontLeading])
    return top + height + 5
  }

  @discardableResult
  func heading(_ text: String, top: CGFloat) -> CGFloat {
    let newTop = drawText(text, x: margin, top: top, width: contentWidth, font: h3Font, color: blue, lineHeight: 14)
    ctx.saveGState()
    ctx.setFillColor(blue.cgColor)
    ctx.fill(CGRect(x: margin, y: page.height - top - 2, width: 4, height: 14))
    ctx.restoreGState()
    return newTop + 1
  }

  func box(_ text: String, x: CGFloat, top: CGFloat, width: CGFloat, height: CGFloat, fill: NSColor = lightBlue, font: NSFont = bodyBold, align: NSTextAlignment = .center) {
    ctx.saveGState()
    ctx.setFillColor(fill.cgColor)
    ctx.fill(rect(x, top, width, height))
    ctx.setStrokeColor(lineColor.cgColor)
    ctx.stroke(rect(x, top, width, height))
    ctx.restoreGState()
    _ = drawText(text, x: x + 8, top: top + 7, width: width - 16, font: font, color: blue, align: align, lineHeight: 13, maxHeight: height - 10)
  }

  @discardableResult
  func table(headers: [String], rows: [[String]], x: CGFloat, top: CGFloat, widths: [CGFloat], font: NSFont = smallFont, headerFont: NSFont = smallBold, lineHeight: CGFloat = 11.4) -> CGFloat {
    let tableWidth = widths.reduce(0, +)
    var y = top
    let allRows = [headers] + rows

    for (rowIndex, row) in allRows.enumerated() {
      let rowFont = rowIndex == 0 ? headerFont : font
      let isHeader = rowIndex == 0
      var cellHeights: [CGFloat] = []
      for (idx, cell) in row.enumerated() {
        let width = widths[idx] - 10
        let attr = NSAttributedString(string: cell, attributes: textAttrs(font: rowFont, color: dark, align: isHeader ? .center : .left, lineHeight: lineHeight))
        let bounds = attr.boundingRect(with: CGSize(width: width, height: 200), options: [.usesLineFragmentOrigin, .usesFontLeading])
        cellHeights.append(max(21, ceil(bounds.height) + 10))
      }
      let rowHeight = cellHeights.max() ?? 22

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
        _ = drawText(cell, x: xCursor + 5, top: y + 5, width: widths[idx] - 10, font: rowFont, color: dark, align: isHeader ? .center : .left, lineHeight: lineHeight, maxHeight: rowHeight - 7)
        xCursor += widths[idx]
      }
      y += rowHeight
    }

    ctx.saveGState()
    ctx.setStrokeColor(lineColor.cgColor)
    ctx.setLineWidth(0.8)
    ctx.stroke(rect(x, top, tableWidth, y - top))
    ctx.restoreGState()
    return y + 6
  }
}

let renderer = Renderer(path: outputPath)

// Page 1
renderer.beginPage(title: "1) 서비스 기획배경 및 필요성")
var y: CGFloat = 66
y = renderer.heading("1. 서비스 기획 배경", top: y)
y = renderer.drawText("제주도는 국내 대표 관광지이지만, 렌터카 없이 이동하는 관광객에게는 일정 운영 난이도가 높은 지역이다. 제주 관광지는 동서남북으로 넓게 분산되어 있고, 관광지와 버스 정류장 사이의 도보 이동, 버스 배차 간격, 환승 가능 여부에 따라 하루 일정이 크게 달라진다. 특히 뚜벅이 여행자는 관광지를 고르는 것보다 ‘그 일정을 실제로 버스 시간에 맞춰 실행할 수 있는지’를 판단하는 데 어려움을 겪는다.", x: margin, top: y, width: contentWidth)
y = renderer.drawText("제주 관광 수요는 충분히 크다. 2024년 제주 방문 관광객은 약 1,378만 명 규모로 집계되었고, 제주관광공사에 따르면 제주 공식 여행 정보 플랫폼 비짓제주는 2024년 1월 1일부터 11월 30일까지 누적 방문자 수 531만 6,274명을 기록했다. 그러나 관광정보 탐색 수요가 높음에도, 렌터카 없이 이동하는 여행자가 버스 시간까지 고려해 하루 일정을 운영하도록 돕는 서비스는 부족하다.", x: margin, top: y, width: contentWidth)
renderer.box("핵심 문제: 관광지 정보 부족이 아니라 ‘이동 타이밍 실패’", x: margin, top: y + 2, width: contentWidth, height: 32)
y += 40
y = renderer.table(
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
  widths: [82, 225, 220]
)
y = renderer.heading("2. 서비스 필요성", top: y + 2)
y = renderer.drawText("버스를 한 번 놓치면 다음 배차까지 긴 대기 시간이 발생할 수 있고, 한 구간의 지연이 이후 관광지 체류 시간과 숙소 도착 시간까지 영향을 준다. 본 서비스의 POC 시나리오에서 ‘제주공항 → 함덕해수욕장 → 월정리해변 → 성산일출봉’ 일정은 이동 자체는 가능했지만, 함덕 이후 구간부터 버스를 놓칠 경우 약 42~48분의 추가 대기 위험이 발생했다.", x: margin, top: y, width: contentWidth)
y = renderer.table(
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
  widths: [238, 289]
)
_ = renderer.drawText("이를 통해 제주를 처음 방문하는 관광객도 버스 중심 일정을 더 예측 가능하게 운영할 수 있다. 또한 렌터카 이용이 어려운 여행자, 청년 여행자, 외국인 관광객에게도 제주 관광 접근성을 높일 수 있다.", x: margin, top: y, width: contentWidth)
renderer.endPage()

// Page 2
renderer.beginPage(title: "2) 서비스 개요")
y = 66
y = renderer.heading("1. 기획 서비스 소개(한 줄 작성)", top: y)
renderer.box("타이밍제주는 TourAPI 관광정보와 제주 버스 시간표를 결합해, 렌터카 없이 제주를 여행하는 뚜벅이의 일정 실패를 줄이는 제주 특화 웹·앱 서비스이다.", x: margin, top: y, width: contentWidth, height: 44)
y += 52
y = renderer.heading("2. 기획 서비스 주요 기능", top: y)
y = renderer.drawText("① TourAPI 기반 관광지 매칭: 사용자가 자연어로 입력한 장소명을 한국관광공사 TourAPI 키워드 검색과 관광지 정보로 실제 관광지 후보와 매칭한다. 관광지명, 주소, 좌표, 이미지, 소개 정보를 일정 카드에 표시하고, 장소명이 애매한 경우 후보를 제시한다.", x: margin, top: y, width: contentWidth)
y = renderer.drawText("② 버스 시간표 기반 일정 안전도 계산: 각 관광지 좌표와 제주 버스 정류소 정보를 비교해 가까운 정류장 후보를 찾고, 시간표 데이터를 기준으로 다음 목적지까지 이동 가능한 버스 후보를 계산한다. 정류장까지의 도보 시간과 안전 버퍼를 반영해 출발 권장 시각을 안내한다.", x: margin, top: y, width: contentWidth)
y = renderer.table(
  headers: ["구간", "출발 권장", "후보 버스", "놓쳤을 때 위험", "상태"],
  rows: [
    ["제주공항 → 함덕해수욕장", "10:00", "10:15", "약 40분 대기", "주의"],
    ["함덕해수욕장 → 월정리해변", "12:10", "12:37", "약 42분 대기", "위험"],
    ["월정리해변 → 성산일출봉", "14:06", "14:58", "약 48분 대기", "위험"],
  ],
  x: margin,
  top: y,
  widths: [170, 78, 78, 135, 66]
)
y = renderer.table(
  headers: ["점수 항목", "점수", "의미"],
  rows: [
    ["경로 성립 점수", "100/100", "모든 구간에 이동 후보가 있음"],
    ["일정 안전도 점수", "38/100", "버스를 놓쳤을 때 대기 위험이 큼"],
    ["종합 실현 가능성 점수", "81/100", "갈 수는 있지만 일정 조정 권장"],
  ],
  x: margin,
  top: y,
  widths: [150, 90, 287]
)
y = renderer.drawText("③ 버스 놓침 방지 라이브 타임라인: 여행 당일 현재 시각을 기준으로 일정을 초록·노랑·빨강 상태로 보여준다. 초록은 여유, 노랑은 출발 준비, 빨강은 일정 위험 상태를 의미한다. 서버가 계산한 출발 권장 시각과 시간표 후보를 기준으로 갱신한다.", x: margin, top: y, width: contentWidth)
y = renderer.drawText("④ 남는 시간 및 버스 놓침 상황의 주변 추천: 버스를 놓쳐 다음 버스까지 시간이 생기거나, 관광지를 예상보다 빨리 둘러본 경우 TourAPI 위치기반 관광정보를 활용해 남은 시간 안에 가능한 주변 관광지, 카페, 포토스팟을 추천한다.", x: margin, top: y, width: contentWidth)
y = renderer.drawText("⑤ 일정 지연 시 복구안 제공: 관광지 체류 시간이 길어져 일정이 밀리면 이후 구간의 버스 후보와 체류 가능 시간을 다시 계산한다. 필수 방문지는 유지하고 선택 관광지 체류 시간을 줄이거나 제외하는 복구안을 제시한다.", x: margin, top: y, width: contentWidth)
renderer.endPage()

// Page 3
renderer.beginPage(title: "2) 서비스 개요 - 차별성 및 지역 특화")
y = 66
y = renderer.heading("3. 서비스 차별성", top: y)
y = renderer.table(
  headers: ["구분", "기존 서비스", "한계", "타이밍제주"],
  rows: [
    ["지도 앱", "A에서 B까지 경로 안내", "하루 전체 일정 위험 판단 어려움", "일정 전체의 버스 타이밍 관리"],
    ["관광 추천 앱", "가볼 만한 장소 추천", "실제 이동 가능 시간 반영 부족", "남은 시간 안에 가능한 장소만 추천"],
    ["AI 여행 플래너", "그럴듯한 일정 생성", "버스 시간 오류 가능성", "TourAPI와 시간표 기반 위험도 검증"],
  ],
  x: margin,
  top: y,
  widths: [75, 135, 157, 160]
)
y = renderer.drawText("OpenAI API를 사용하더라도 버스 시간이나 경로 가능 여부를 AI가 임의로 생성하지 않는다. 버스 시간, 출발 권장 시각, 위험도는 서버의 계산 엔진이 담당하고, OpenAI는 자연어 일정 분석과 설명 보조 역할을 담당한다. 따라서 서비스의 핵심은 생성형 AI가 아니라, TourAPI 관광정보와 제주 버스 데이터를 결합한 실행 가능한 일정 운영이다.", x: margin, top: y, width: contentWidth)
renderer.box("운영 원칙: 버스 시간과 경로 판단은 서버가 계산하고, AI는 계산된 사실을 이해하기 쉽게 설명한다.", x: margin, top: y + 2, width: contentWidth, height: 36)
y += 44
y = renderer.heading("4. (지역 특화 서비스의 경우 작성 필수) 서비스 내 지역 특화 관련 사항", top: y)
y = renderer.drawText("타이밍제주는 전국 범용 여행 서비스가 아니라 제주 지역 특화 서비스다. 제주 관광은 관광지 간 거리가 길고, 동서남북 관광권이 넓게 나뉘며, 렌터카 여부에 따라 여행 경험이 크게 달라진다. 특히 뚜벅이 관광객은 버스 배차와 정류장 위치를 고려하지 않으면 일정 실패 가능성이 높다.", x: margin, top: y, width: contentWidth)
y = renderer.drawText("공모전 MVP 범위는 제주 동쪽 대표 관광 코리도어다. 주요 대상지는 제주공항, 함덕해수욕장, 월정리해변, 성산일출봉, 섭지코지, 성산·서귀포 숙소권이다. 이 범위는 제주 뚜벅이 관광객이 많이 선택하는 동선이며, 버스 배차 간격에 따른 일정 위험을 명확히 보여줄 수 있다.", x: margin, top: y, width: contentWidth)
y = renderer.table(
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
  widths: [150, 220, 157]
)
y = renderer.heading("심사 기준 대응 요약", top: y)
_ = renderer.table(
  headers: ["심사항목", "대응 전략", "강조 포인트"],
  rows: [
    ["서비스 기획력", "관광지 추천이 아닌 일정 실패 문제 해결", "문제 구체성, 독창성"],
    ["서비스 완성도", "동쪽 코리도어 중심 MVP 구현", "기능심사 시연 가능"],
    ["데이터 활용 적절성", "TourAPI 장소 매칭·주변 추천 핵심 사용", "OpenAPI 필수 활용"],
    ["서비스 발전성", "제주 전역·RTO 대시보드 확장", "지속성, 확장성"],
  ],
  x: margin,
  top: y,
  widths: [110, 250, 167]
)
renderer.endPage()

// Page 4
renderer.beginPage(title: "3) 데이터 활용 방안")
y = 66
y = renderer.heading("1. 활용 예정 한국관광공사 OpenAPI", top: y)
y = renderer.drawText("활용 예정 한국관광공사 OpenAPI는 한국관광공사 국문 관광정보 서비스 TourAPI이다. 공공데이터포털 기준 해당 API는 REST 방식, JSON+XML 포맷을 제공하며, 지역기반 관광정보, 위치기반 관광정보, 키워드검색, 행사정보, 숙박정보, 공통정보, 소개정보, 이미지정보 등 15종 약 26만 건의 국내 관광정보를 제공한다. 또한 개발계정 기준 1,000건, 운영계정은 활용사례 등록 후 트래픽 증가 신청이 가능하다.", x: margin, top: y, width: contentWidth)
renderer.box("TourAPI가 없다면 장소 매칭, 관광지 카드, 주변 추천 기능의 핵심 품질을 확보하기 어렵다.", x: margin, top: y + 1, width: contentWidth, height: 33)
y += 40
y = renderer.heading("2. 데이터 활용 방식", top: y)
y = renderer.table(
  headers: ["데이터", "활용 방식", "서비스 기능"],
  rows: [
    ["TourAPI 키워드 검색", "사용자가 입력한 장소명을 실제 관광지 후보로 매칭", "일정 생성"],
    ["TourAPI 위치기반 관광정보", "현재 위치 주변의 관광지, 음식점, 문화시설 후보 조회", "남는 시간 추천"],
    ["TourAPI 이미지정보", "추천 카드와 일정 카드에 관광지 이미지 표시", "사용자 선택 지원"],
    ["TourAPI 공통·소개정보", "관광지 설명, 주소, 좌표, 카테고리 표시", "관광지 상세"],
    ["TourAPI 숙박정보", "숙소 주변 일정 추천 및 도착지 설정에 활용", "일정 마무리"],
    ["TourAPI 행사정보", "여행 날짜에 맞는 주변 행사 후보 추천", "대체 일정"],
  ],
  x: margin,
  top: y,
  widths: [145, 262, 120]
)
y = renderer.drawText("추가 데이터는 제주 버스 정류소 기본 정보, 제주 버스 노선 기본 정보, 제주 버스 시간표 정보, Kakao Maps Web API, OpenAI API를 활용한다.", x: margin, top: y, width: contentWidth)
y = renderer.table(
  headers: ["추가 데이터", "활용 방식"],
  rows: [
    ["제주 버스 정류소 기본 정보", "관광지 주변 정류장 후보 탐색"],
    ["제주 버스 노선 기본 정보", "구간별 노선 후보 표시"],
    ["제주 버스 시간표 정보", "출발 권장 시각, 대기 위험, 놓쳤을 때 추가 대기 계산"],
    ["Kakao Maps Web API", "관광지와 정류장 위치 표시"],
    ["OpenAI API", "자연어 일정 파싱, 일정 위험 설명, 복구안 설명"],
  ],
  x: margin,
  top: y,
  widths: [205, 322]
)
y = renderer.heading("데이터 정확도 관리", top: y)
_ = renderer.table(
  headers: ["위험", "대응"],
  rows: [
    ["TourAPI 장소명이 여러 개 매칭", "사용자 선택 UI 제공"],
    ["정류장 방향·실제 승차 위치 혼동", "가까운 정류장 후보로 표현하고 지도 확인 유도"],
    ["실시간 지연·결행", "MVP는 시간표 기반 안내임을 명시"],
    ["공공 API 일시 실패", "데이터 상태 화면과 fallback 여부 표시"],
  ],
  x: margin,
  top: y,
  widths: [190, 337]
)
renderer.endPage()

// Page 5
renderer.beginPage(title: "4) 서비스 발전 방향")
y = 66
y = renderer.heading("1. 개발 서비스 향후 발전방향", top: y)
y = renderer.drawText("1단계는 공모전 MVP 완성이다. 공모전 개발 기간에는 제주 동쪽 코리도어 중심으로 핵심 기능을 완성한다. 주요 목표는 TourAPI 장소 매칭, 시간표 기반 출발 권장 시각 계산, 일정 안전도 표시, 버스 놓침 시 주변 추천, 일정 지연 시 복구안 제공이다.", x: margin, top: y, width: contentWidth)
y = renderer.drawText("2단계는 제주 전역 확장이다. MVP에서 검증한 구조를 바탕으로 제주 전역의 정류장, 노선, 시간표 데이터를 확대 적용한다. 노선별 정류장 순서와 시간표 정합성을 검증한 뒤, 더 많은 지역의 뚜벅이 여행 일정을 지원한다.", x: margin, top: y, width: contentWidth)
y = renderer.drawText("3단계는 실시간 보정 및 다국어 안내다. 정류장별 도착예정 정보 또는 버스 위치 데이터 활용 가능성이 확보되면 시간표 기반 안내에 실시간 보정을 추가한다. 또한 외국인 관광객을 위해 한국어 일정 안내를 영어·중국어·일본어 등으로 제공한다.", x: margin, top: y, width: contentWidth)
y = renderer.drawText("4단계는 지역관광 운영 데이터로의 확장이다. 사용자가 어떤 구간에서 자주 지연되는지, 어떤 관광지에서 대기 시간이 자주 발생하는지 분석하면 제주 지역 관광 운영에도 활용할 수 있다. 향후에는 RTO나 지자체가 뚜벅이 관광객의 이동 불편 구간을 파악하고, 관광지 분산 방문과 대중교통 기반 관광 활성화에 참고할 수 있는 대시보드로 발전시킬 수 있다.", x: margin, top: y, width: contentWidth)
y = renderer.table(
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
  widths: [110, 230, 187]
)
y = renderer.heading("정량 기대효과 및 제출 시 강조 수치", top: y)
y = renderer.table(
  headers: ["항목", "수치/목표", "의미"],
  rows: [
    ["제주 관광 수요", "2024년 약 1,378만 명", "충분한 대상 시장"],
    ["비짓제주 온라인 수요", "2024.1.1~11.30 누적 531만 6,274명", "온라인 관광정보 활용 수요"],
    ["TourAPI 규모", "15종 약 26만 건", "관광지 매칭·주변 추천 데이터 기반"],
    ["POC 대기 위험", "약 42~48분", "버스 놓침 문제의 구체성"],
    ["MVP 기능 목표", "TourAPI 매칭·일정 안전도·복구안 시연", "기능심사 대응"],
  ],
  x: margin,
  top: y,
  widths: [130, 190, 207]
)
y = renderer.heading("공모전 심사기준 대응", top: y)
y = renderer.table(
  headers: ["심사항목", "제안서 반영 내용", "점수 확보 논리"],
  rows: [
    ["서비스 기획력", "제주 뚜벅이의 이동 타이밍 실패 문제를 구체화", "문제 정의가 명확하고 제주 상황에 특화됨"],
    ["서비스 완성도", "동쪽 코리도어 MVP로 기능 범위를 제한", "5월~9월 개발 기간 내 실제 기능심사 대응 가능"],
    ["데이터 활용 적절성", "TourAPI를 장소 매칭·주변 추천의 핵심 데이터로 사용", "공사 OpenAPI가 없으면 핵심 기능이 약해지는 구조"],
    ["서비스 발전성", "제주 전역·실시간 보정·RTO 대시보드로 확장", "개인 여행자 서비스에서 지역관광 운영 도구로 발전"],
  ],
  x: margin,
  top: y,
  widths: [110, 230, 187]
)
y = renderer.heading("최종 기대효과", top: y)
_ = renderer.drawText("타이밍제주는 제주 뚜벅이 관광객의 버스 대기 시간 감소, 일정 실패와 관광지 포기 경험 감소, 렌터카 없이도 가능한 제주 여행 경험 확대, TourAPI 관광정보 활용도 증대, 제주 지역특화 관광 서비스 경쟁력 강화, 제주 RTO와 연계 가능한 관광 이동 데이터 기반 마련을 기대효과로 한다. 공모전 기간에는 완성도 높은 제주 동쪽 코리도어 MVP를 구현하고, 이후 데이터 검증 범위를 넓혀 제주 전역 일정 운영 서비스로 확장한다.", x: margin, top: y, width: contentWidth)
renderer.endPage()

renderer.save(to: outputPath)
