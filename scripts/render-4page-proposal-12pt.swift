import AppKit
import CoreGraphics
import Foundation

let outputPath = CommandLine.arguments.count > 1
  ? CommandLine.arguments[1]
  : "docs/proposals/timing-jeju-4page-12pt-proposal.pdf"

let page = CGRect(x: 0, y: 0, width: 595, height: 842)
let margin: CGFloat = 42
let contentWidth = page.width - margin * 2
let footerTop: CGFloat = 812

let bodyFont = NSFont(name: "AppleSDGothicNeo-Regular", size: 12) ?? NSFont.systemFont(ofSize: 12)
let bodyBold = NSFont(name: "AppleSDGothicNeo-Bold", size: 12) ?? NSFont.boldSystemFont(ofSize: 12)
let h1Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 17) ?? NSFont.boldSystemFont(ofSize: 17)
let h2Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 14.5) ?? NSFont.boldSystemFont(ofSize: 14.5)
let h3Font = NSFont(name: "AppleSDGothicNeo-Bold", size: 13) ?? NSFont.boldSystemFont(ofSize: 13)

let dark = NSColor(calibratedWhite: 0.07, alpha: 1)
let blue = NSColor(calibratedRed: 0.02, green: 0.20, blue: 0.42, alpha: 1)
let lightBlue = NSColor(calibratedRed: 0.92, green: 0.96, blue: 1.0, alpha: 1)
let lineColor = NSColor(calibratedWhite: 0.50, alpha: 1)

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

  func beginPage(_ title: String) {
    pageNo += 1
    ctx.beginPDFPage(nil)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
    ctx.saveGState()
    ctx.setFillColor(NSColor.white.cgColor)
    ctx.fill(page)
    ctx.restoreGState()

    drawText("『2026 관광데이터 활용 공모전』 - ① 웹·앱 개발 부문 제안서", x: margin, top: 22, width: contentWidth, font: h1Font, align: .center, lineHeight: 20)
    drawRule(top: 50)
    drawText(title, x: margin, top: 60, width: contentWidth, font: h2Font, color: blue, lineHeight: 17)
  }

  func endPage() {
    drawRule(top: 802)
    drawText("타이밍제주 | 12pt 이상 · 4페이지 작성본", x: margin, top: 811, width: contentWidth - 60, font: bodyFont, color: NSColor.darkGray, lineHeight: 13)
    drawText("\(pageNo) / 4", x: page.width - margin - 55, top: 811, width: 55, font: bodyFont, color: NSColor.darkGray, align: .right, lineHeight: 13)
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
  func drawText(_ text: String, x: CGFloat, top: CGFloat, width: CGFloat, font: NSFont = bodyFont, color: NSColor = dark, align: NSTextAlignment = .left, lineHeight: CGFloat = 14.4, maxHeight: CGFloat = 1_000) -> CGFloat {
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

  @discardableResult
  func callout(_ text: String, top: CGFloat, height: CGFloat = 44) -> CGFloat {
    ctx.saveGState()
    ctx.setFillColor(lightBlue.cgColor)
    ctx.fill(rect(margin, top, contentWidth, height))
    ctx.setStrokeColor(lineColor.cgColor)
    ctx.stroke(rect(margin, top, contentWidth, height))
    ctx.restoreGState()
    _ = drawText(text, x: margin + 9, top: top + 8, width: contentWidth - 18, font: bodyBold, color: blue, align: .center, lineHeight: 14.5, maxHeight: height - 10)
    return top + height + 8
  }

  @discardableResult
  func table(headers: [String], rows: [[String]], top: CGFloat, widths: [CGFloat]) -> CGFloat {
    var y = top
    let allRows = [headers] + rows
    for (rowIndex, row) in allRows.enumerated() {
      let isHeader = rowIndex == 0
      let rowFont = isHeader ? bodyBold : bodyFont
      var rowHeight: CGFloat = 28
      for (idx, cell) in row.enumerated() {
        let attr = NSAttributedString(string: cell, attributes: attrs(font: rowFont, color: dark, align: isHeader ? .center : .left, lineHeight: 13.8))
        let bounds = attr.boundingRect(with: CGSize(width: widths[idx] - 10, height: 240), options: [.usesLineFragmentOrigin, .usesFontLeading])
        rowHeight = max(rowHeight, ceil(bounds.height) + 11)
      }

      var xCursor = margin
      for (idx, cell) in row.enumerated() {
        let cellRect = rect(xCursor, y, widths[idx], rowHeight)
        ctx.saveGState()
        ctx.setFillColor((isHeader ? lightBlue : NSColor.white).cgColor)
        ctx.fill(cellRect)
        ctx.setStrokeColor(lineColor.cgColor)
        ctx.setLineWidth(0.45)
        ctx.stroke(cellRect)
        ctx.restoreGState()
        _ = drawText(cell, x: xCursor + 5, top: y + 5, width: widths[idx] - 10, font: rowFont, align: isHeader ? .center : .left, lineHeight: 13.8, maxHeight: rowHeight - 8)
        xCursor += widths[idx]
      }
      y += rowHeight
    }
    return y + 8
  }

  func warnIfOverflow(_ y: CGFloat) {
    if y > footerTop {
      fputs("WARN page \(pageNo) overflow: \(y)\n", stderr)
    }
  }
}

let r = Renderer()
var y: CGFloat = 86

// Page 1
r.beginPage("1) 서비스 기획배경 및 필요성")
y = 86
y = r.heading("1. 서비스 기획 배경", top: y)
y = r.drawText("제주도는 국내 대표 관광지이지만 렌터카 없이 이동하는 관광객에게는 일정 운영 난도가 높다. 관광지가 동서남북으로 넓게 분산되어 있고, 관광지와 정류장 사이의 도보 이동, 버스 배차 간격, 환승 가능 여부가 하루 일정의 성패를 좌우한다. 기존 지도 앱은 A에서 B까지의 경로를 알려주지만, 여행자가 하루 전체 일정을 기준으로 언제 나와야 하는지와 버스를 놓쳤을 때 무엇을 조정해야 하는지는 판단하기 어렵다.", x: margin, top: y, width: contentWidth)
y = r.drawText("제주 관광 수요는 충분하다. 2024년 제주 방문 관광객은 약 1,378만 명 규모이며, 제주관광공사 비짓제주는 2024년 1월 1일부터 11월 30일까지 누적 방문자 531만 6,274명을 기록했다. 즉 온라인 관광정보 탐색 수요는 크지만, 뚜벅이 관광객의 이동 타이밍 실패를 줄이는 전용 서비스는 부족하다.", x: margin, top: y, width: contentWidth)
y = r.callout("핵심 문제: 관광지 정보 부족이 아니라 ‘버스 시간과 맞지 않는 일정 운영’", top: y)
y = r.heading("2. 서비스 필요성", top: y)
y = r.drawText("20살 제주 뚜벅이 여행 중 버스 배차 간격을 놓쳐 최대 1시간 가까이 기다린 경험에서 아이디어가 출발했다. 한 번의 긴 대기는 다음 관광지 체류시간, 식사, 숙소 도착시간까지 밀리게 하므로 단순 불편이 아니라 일정 실패 요인이다. POC 시나리오에서도 ‘제주공항 → 함덕해수욕장 → 월정리해변 → 성산일출봉’ 일정은 이동 자체는 가능했지만, 구간별 약 42~48분의 추가 대기 위험이 확인됐다.", x: margin, top: y, width: contentWidth)
y = r.table(
  headers: ["현재 문제", "사용자 불편", "타이밍제주 해결"],
  rows: [
    ["정류장 탐색", "관광지 주변 정류장과 도보 시간을 직접 확인", "관광지 좌표 기준 가까운 정류장 후보 제시"],
    ["출발 시각 판단", "버스 시간표와 체류시간을 직접 계산", "정류장 도보시간과 안전버퍼를 반영한 출발 권장 시각 제공"],
    ["버스 놓침", "다음 일정 전체가 밀릴 수 있음", "놓쳤을 때 추가 대기 위험과 대체 일정 안내"],
    ["남는 시간", "가까운 장소를 임의 검색", "남은 시간 안에 실제 가능한 주변 관광지 추천"],
  ],
  top: y,
  widths: [110, 205, 196]
)
y = r.drawText("따라서 타이밍제주는 단순 관광지 추천 앱이 아니라 제주 뚜벅이 여행자의 하루 일정을 실시간으로 운영하는 서비스다. 사용자는 버스 시간 때문에 일정이 무너지는 위험을 사전에 알고, 남는 시간은 관광 경험으로 전환할 수 있다.", x: margin, top: y, width: contentWidth)
r.warnIfOverflow(y)
r.endPage()

// Page 2
r.beginPage("2) 서비스 개요")
y = 86
y = r.heading("1. 기획 서비스 소개(한 줄 작성)", top: y)
y = r.callout("타이밍제주는 TourAPI 관광정보와 제주 버스 데이터를 결합해 렌터카 없이 제주를 여행하는 뚜벅이의 일정 실패를 줄이는 제주 특화 웹·앱 서비스이다.", top: y, height: 50)
y = r.heading("2. 기획 서비스 주요 기능", top: y)
y = r.table(
  headers: ["기능", "구현 내용", "사용자 가치"],
  rows: [
    ["AI 일정 입력", "자연어로 입력한 장소·시간·취향을 일정 구조로 변환", "복잡한 계획 입력 부담 감소"],
    ["TourAPI 장소 매칭", "관광지명, 주소, 좌표, 이미지, 소개 정보를 일정 카드에 표시", "입력 장소 오류와 혼동 감소"],
    ["버스 타이밍 계산", "정류장 후보, 도보 시간, 시간표, 안전버퍼를 합산", "언제 나와야 하는지 명확히 안내"],
    ["일정 안전도", "경로 성립, 대기 위험, 환승 여유, 대체 노선 수를 점수화", "무리한 일정 여부를 한눈에 판단"],
    ["라이브 여행 모드", "초록·노랑·빨강 상태로 현재 일정 위험 표시", "관광 중에도 다음 버스 타이밍 감시"],
    ["남는 시간 추천", "다음 버스 전까지 가능한 주변 관광지·카페·포토스팟 추천", "대기 시간을 관광 경험으로 전환"],
    ["AI 재일정", "필수 방문지는 유지하고 선택 일정을 줄이거나 순서 변경", "지연 시 하루 일정 붕괴 방지"],
  ],
  top: y,
  widths: [105, 255, 151]
)
y = r.heading("서비스 이용 흐름", top: y)
y = r.drawText("사용자는 ‘제주공항 도착 후 함덕, 월정리, 성산일출봉을 가고 싶다’처럼 입력한다. 서비스는 TourAPI로 장소 후보를 확인하고, 제주 정류소·노선·시간표 데이터로 이동 가능성을 계산한다. 이후 출발 권장 시각, 놓쳤을 때 추가 대기 시간, 일정 안전도, 남는 시간 추천, 지연 시 복구안을 한 화면에서 제공한다.", x: margin, top: y, width: contentWidth)
r.warnIfOverflow(y)
r.endPage()

// Page 3
r.beginPage("2) 서비스 개요 - 차별성 및 지역 특화")
y = 86
y = r.heading("3. 서비스 차별성", top: y)
y = r.table(
  headers: ["구분", "기존 서비스 한계", "타이밍제주 차별성"],
  rows: [
    ["지도 앱", "A에서 B까지의 단일 경로 안내 중심", "하루 전체 일정에서 버스 타이밍과 체류시간을 함께 관리"],
    ["관광 추천 앱", "가볼 만한 장소를 추천하지만 실제 이동 가능 시간 반영 부족", "남은 시간 안에 실제 가능한 후보만 추천"],
    ["일반 AI 여행 플래너", "그럴듯한 일정 생성은 가능하지만 버스 시간 오류 위험 존재", "버스 시간·위험도는 서버 계산 엔진이 처리하고 AI는 설명만 담당"],
    ["제주 여행 정보 서비스", "관광지 정보 제공 중심", "관광정보를 이동 의사결정 데이터로 전환"],
  ],
  top: y,
  widths: [90, 220, 201]
)
y = r.drawText("OpenAI API는 버스 시간이나 이동 가능 여부를 임의 생성하지 않는다. 사용자의 자연어 입력을 구조화하고, 서버가 계산한 출발 권장 시각·위험도·대체안을 이해하기 쉬운 문장으로 설명하는 역할을 맡는다. 이 구조를 통해 AI 활용성과 정확도 사이의 균형을 잡는다.", x: margin, top: y, width: contentWidth)
y = r.heading("4. (지역 특화 서비스의 경우 작성 필수) 서비스 내 지역 특화 관련 사항", top: y)
y = r.drawText("타이밍제주는 전국 범용 여행 서비스가 아니라 제주 지역 특화 서비스다. 제주 관광은 관광지 간 거리가 길고, 동서남북 관광권이 뚜렷하며, 렌터카 여부에 따라 여행 경험이 크게 달라진다. 특히 뚜벅이 관광객은 버스 배차와 정류장 위치를 고려하지 않으면 한 번의 지연이 다음 관광지 체류시간과 숙소 도착시간까지 연쇄적으로 영향을 준다.", x: margin, top: y, width: contentWidth)
y = r.table(
  headers: ["제주 특화 요소", "서비스 반영 방식", "심사 관점"],
  rows: [
    ["관광지 분산성", "하루 전체 일정 기준 위험도 계산", "문제 구체성"],
    ["버스 배차 간격", "놓쳤을 때 추가 대기 시간 표시", "실용성"],
    ["정류장-관광지 도보 이동", "정류장 후보와 도보 시간 반영", "편의성"],
    ["동쪽 관광 코리도어", "제주공항·함덕·월정리·성산 중심 MVP 구현", "완성도"],
    ["지역특화 서비스", "제주 단일 지역 문제에 집중", "지역특화 가점 및 RTO 특별상 대응"],
  ],
  top: y,
  widths: [155, 235, 121]
)
y = r.drawText("MVP는 제주 동쪽 대표 관광 코리도어를 우선 대상으로 삼아 기능심사에서 실제 동선 기반 시연이 가능하도록 구현한다. 이후 같은 구조를 제주 전역으로 확장한다.", x: margin, top: y, width: contentWidth)
r.warnIfOverflow(y)
r.endPage()

// Page 4
r.beginPage("3) 데이터 활용 방안 / 4) 서비스 발전 방향")
y = 86
y = r.heading("1. 활용 예정 한국관광공사 OpenAPI", top: y)
y = r.drawText("활용 예정 한국관광공사 OpenAPI는 한국관광공사 국문 관광정보 서비스 TourAPI이다. TourAPI의 키워드검색, 위치기반 관광정보, 지역기반 관광정보, 이미지정보, 공통정보, 소개정보, 행사정보, 숙박정보를 활용해 관광지 후보와 상세 정보를 구성한다.", x: margin, top: y, width: contentWidth)
y = r.heading("2. 데이터 활용 방식", top: y)
y = r.table(
  headers: ["데이터/API", "활용 방식", "서비스 기능"],
  rows: [
    ["TourAPI 키워드검색", "사용자 입력 장소명을 관광지 후보로 매칭", "일정 생성"],
    ["TourAPI 위치기반 관광정보", "현재 위치 반경 후보 조회", "남는 시간 추천"],
    ["TourAPI 이미지·공통·소개정보", "이미지, 주소, 좌표, 카테고리, 설명 표시", "관광지 상세 카드"],
    ["제주 정류소·노선·시간표", "정류장 후보, 도보 시간, 출발 권장 시각, 대기 위험 계산", "일정 운영"],
    ["OpenAI API", "자연어 일정 파싱, 취향 추출, 위험도 설명", "AI 일정 매니저"],
  ],
  top: y,
  widths: [150, 245, 116]
)
y = r.drawText("정확도 원칙은 명확하다. 버스 시간, 경로 가능 여부, 일정 안전도는 서버의 계산 엔진이 담당하고, OpenAI API는 자연어 이해와 설명 보조에 한정한다. MVP에서는 시간표 기반 안내를 먼저 구현하고, 정류장별 도착 예정 정보나 버스 위치 데이터 활용이 가능해지면 실시간 보정값을 더한다.", x: margin, top: y, width: contentWidth)
y = r.heading("1. 개발 서비스 향후 발전방향", top: y)
y = r.table(
  headers: ["단계", "주요 내용", "기대효과"],
  rows: [
    ["MVP", "제주 동쪽 코리도어 일정 운영, TourAPI 장소 매칭, 시간표 기반 출발 시각 계산", "기능심사 시연 가능"],
    ["제주 전역 확장", "정류장·노선·시간표 범위 확대", "더 많은 뚜벅이 일정 지원"],
    ["실시간 보정", "버스 위치·도착 예정 정보 반영", "안내 정확도 향상"],
    ["다국어 안내", "영어·중국어·일본어 일정 및 탑승 안내", "외국인 관광객 접근성 향상"],
    ["RTO 대시보드", "대기 위험 구간과 이동 불편 구간 분석", "지역관광 정책 데이터 활용"],
  ],
  top: y,
  widths: [100, 275, 136]
)
y = r.drawText("기대효과는 사용자와 지역 모두에 있다. 사용자는 렌터카 없이도 예측 가능한 제주 일정을 운영할 수 있고, 지역은 관광객이 특정 관광지에만 몰리지 않도록 주변 장소와 대체 동선을 추천받는 효과를 얻는다. 또한 공사 OpenAPI는 단순 정보 조회가 아니라 실제 여행 의사결정의 핵심 데이터로 활용된다.", x: margin, top: y, width: contentWidth)
r.warnIfOverflow(y)
r.endPage()

r.save(to: outputPath)
