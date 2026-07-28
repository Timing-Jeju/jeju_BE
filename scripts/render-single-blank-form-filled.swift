import AppKit
import CoreGraphics
import Foundation
import PDFKit

let blankFormPath = "/Users/josephuk77/Downloads/(양식1)『2026 관광데이터 활용 공모전』 제안서_팀명(대표자명) (1).pdf"
let outputPath = CommandLine.arguments.count > 1
  ? CommandLine.arguments[1]
  : "docs/proposals/timing-jeju-single-form-filled.pdf"

let page = CGRect(x: 0, y: 0, width: 595, height: 842)
let bodyFont = NSFont(name: "AppleSDGothicNeo-Regular", size: 12) ?? NSFont.systemFont(ofSize: 12)
let dark = NSColor(calibratedWhite: 0.08, alpha: 1)

struct Box {
  let x: CGFloat
  let top: CGFloat
  let width: CGFloat
  let height: CGFloat
}

let boxes = (
  background: Box(x: 61, top: 242, width: 475, height: 70),
  overview: Box(x: 61, top: 382, width: 475, height: 95),
  data: Box(x: 61, top: 523, width: 475, height: 63),
  growth: Box(x: 61, top: 642, width: 475, height: 56)
)

final class Renderer {
  let ctx: CGContext
  let data = NSMutableData()

  init() {
    var box = page
    let consumer = CGDataConsumer(data: data as CFMutableData)!
    ctx = CGContext(consumer: consumer, mediaBox: &box, nil)!
  }

  func save(to path: String) {
    ctx.closePDF()
    data.write(toFile: path, atomically: true)
  }

  func beginPage() {
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
    NSGraphicsContext.restoreGraphicsState()
    ctx.endPDFPage()
  }

  func rect(_ box: Box) -> CGRect {
    CGRect(x: box.x, y: page.height - box.top - box.height, width: box.width, height: box.height)
  }

  func draw(_ text: String, in box: Box, lineHeight: CGFloat = 14.0) {
    let style = NSMutableParagraphStyle()
    style.minimumLineHeight = lineHeight
    style.maximumLineHeight = lineHeight
    style.lineBreakMode = .byWordWrapping
    let attrs: [NSAttributedString.Key: Any] = [
      .font: bodyFont,
      .foregroundColor: dark,
      .paragraphStyle: style,
    ]
    let attributed = NSAttributedString(string: text, attributes: attrs)
    let bounds = attributed.boundingRect(with: CGSize(width: box.width, height: 10_000), options: [.usesLineFragmentOrigin, .usesFontLeading])
    if ceil(bounds.height) > box.height {
      fputs("WARN: text height \(ceil(bounds.height)) exceeds box height \(box.height)\n", stderr)
    }
    attributed.draw(with: rect(box), options: [.usesLineFragmentOrigin, .usesFontLeading])
  }
}

let contentBackground = """
1. 서비스 기획 배경: 제주 관광지는 넓게 분산되어 버스 배차·환승·정류장 도보 이동이 일정 실패로 이어진다.
2. 서비스 필요성: TourAPI 관광정보와 제주 버스 데이터를 결합해 출발 권장 시각, 대기 위험, 대체 일정, 남는 시간 추천을 제공해야 한다.
"""

let contentOverview = """
1. 기획 서비스 소개: 제주 뚜벅이의 버스 시간 기반 AI 일정 운영 서비스 ‘타이밍제주’.
2. 주요 기능: 관광지 매칭, 정류장 탐색, 출발 권장 시각, 일정 안전도, 버스 놓침 시 주변 추천, 지연 시 AI 재일정.
3. 차별성: 지도 앱처럼 구간 경로만 안내하지 않고 하루 전체 버스 타이밍을 관리한다.
4. 지역 특화: 제주 동쪽 코리도어 MVP 후 제주 전역으로 확장한다.
"""

let contentData = """
1. 활용 예정 한국관광공사 OpenAPI: 국문관광정보 TourAPI.
2. 데이터 활용 방식: 키워드검색·위치기반·이미지·공통·소개정보로 관광지 후보를 만들고, 제주 정류소·노선·시간표와 결합해 실행 가능한 일정을 계산한다.
"""

let contentGrowth = """
1. 개발 서비스 향후 발전방향: 공모전 기간에는 제주 동쪽 MVP를 완성하고, 이후 제주 전역 확대, 실시간 버스 보정, 다국어 안내, 사용자 체류시간 학습, RTO 대시보드로 확장한다.
"""

let renderer = Renderer()
renderer.beginPage()
renderer.draw(contentBackground, in: boxes.background)
renderer.draw(contentOverview, in: boxes.overview)
renderer.draw(contentData, in: boxes.data)
renderer.draw(contentGrowth, in: boxes.growth)
renderer.endPage()
renderer.save(to: outputPath)
