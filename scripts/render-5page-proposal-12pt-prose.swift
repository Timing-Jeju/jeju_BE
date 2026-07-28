import AppKit
import CoreGraphics
import Foundation

let outputPath = CommandLine.arguments.count > 1
  ? CommandLine.arguments[1]
  : "docs/proposals/timing-jeju-5page-12pt-prose-proposal.pdf"

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
    drawText("타이밍제주 | 12pt 이상 · 5페이지 본문형 작성본", x: margin, top: 811, width: contentWidth - 70, font: bodyFont, color: NSColor.darkGray, lineHeight: 13)
    drawText("\(pageNo) / 5", x: page.width - margin - 55, top: 811, width: 55, font: bodyFont, color: NSColor.darkGray, align: .right, lineHeight: 13)
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
  func drawText(_ text: String, x: CGFloat, top: CGFloat, width: CGFloat, font: NSFont = bodyFont, color: NSColor = dark, align: NSTextAlignment = .left, lineHeight: CGFloat = 14.5, maxHeight: CGFloat = 1_000) -> CGFloat {
    let attributed = NSAttributedString(string: text, attributes: attrs(font: font, color: color, align: align, lineHeight: lineHeight))
    let bounds = attributed.boundingRect(with: CGSize(width: width, height: maxHeight), options: [.usesLineFragmentOrigin, .usesFontLeading])
    let height = min(ceil(bounds.height) + 2, maxHeight)
    attributed.draw(with: rect(x, top, width, height), options: [.usesLineFragmentOrigin, .usesFontLeading])
    return top + height + 6
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
y = r.drawText("제주도는 국내 대표 관광지이지만 렌터카 없이 이동하는 여행자에게는 일정 운영 난도가 높은 지역이다. 관광지가 동서남북으로 넓게 분산되어 있고, 관광지와 정류장 사이의 도보 이동, 버스 배차 간격, 환승 가능 여부가 하루 일정의 성패를 좌우한다. 기존 지도 앱은 A에서 B까지의 이동 경로를 알려주지만, 여행자가 하루 전체 일정을 기준으로 언제 나와야 하는지, 더 머물러도 되는지, 버스를 놓쳤을 때 어떤 일정을 조정해야 하는지는 판단하기 어렵다.", x: margin, top: y, width: contentWidth)
y = r.drawText("제주 관광 수요는 충분하다. 2024년 제주 방문 관광객은 약 1,378만 명 규모이며, 제주관광공사 비짓제주는 2024년 1월 1일부터 11월 30일까지 누적 방문자 531만 6,274명을 기록했다. 관광정보를 온라인에서 탐색하는 수요는 이미 크지만, 렌터카 없이 이동하는 관광객이 버스 시간까지 반영해 하루 일정을 운영하도록 돕는 서비스는 부족하다.", x: margin, top: y, width: contentWidth)
y = r.drawText("타이밍제주는 이 문제를 제주 뚜벅이 여행자의 실제 경험에서 출발해 해결하려는 서비스다. 20살 제주 뚜벅이 여행 중 버스 배차 간격을 제대로 확인하지 못해 최대 1시간 가까이 기다린 경험이 있었고, 그 대기는 다음 관광지 체류시간과 식사, 숙소 도착시간까지 밀리게 만들었다. 이 경험은 제주 대중교통 여행에서 버스 한 대를 놓치는 일이 단순 불편이 아니라 하루 전체 일정 실패로 이어질 수 있음을 보여준다.", x: margin, top: y, width: contentWidth)
y = r.heading("2. 서비스 필요성", top: y)
y = r.drawText("POC 시나리오에서도 동일한 문제가 확인됐다. ‘제주공항 → 함덕해수욕장 → 월정리해변 → 성산일출봉’ 일정은 이동 자체는 가능했지만, 함덕 이후 구간부터 버스를 놓칠 경우 구간별 약 42~48분의 추가 대기 위험이 발생했다. 겉보기에는 자연스러운 제주 동쪽 코스라도, 버스 시간표와 정류장 도보 시간을 반영하면 일정 안전도가 낮아질 수 있다.", x: margin, top: y, width: contentWidth)
y = r.drawText("따라서 필요한 서비스는 단순한 관광지 추천 앱이 아니라 여행 당일의 시간을 운영하는 일정 매니저다. 타이밍제주는 관광지별 가까운 정류장 후보, 정류장까지의 도보 시간, 시간표 기준 출발 권장 시각, 버스를 놓쳤을 때의 추가 대기 위험, 남는 시간 안에 가능한 주변 관광지, 지연 시 대체 일정을 함께 안내한다. 이를 통해 사용자는 버스 시간 때문에 일정이 무너질 위험을 사전에 알고, 대기 시간을 관광 경험으로 전환할 수 있다.", x: margin, top: y, width: contentWidth)
y = r.drawText("이 서비스가 필요한 이유는 제주 관광의 병목이 정보 부족에서 실행 실패로 이동했기 때문이다. 관광지명, 사진, 후기, 위치 정보는 이미 다양한 서비스에서 제공되지만, 뚜벅이 관광객에게 가장 중요한 질문인 ‘지금 출발하지 않으면 다음 일정이 얼마나 위험해지는가’에는 명확히 답하지 못한다. 타이밍제주는 TourAPI의 관광정보를 단순 조회용 데이터가 아니라, 버스 시간과 연결된 여행 의사결정 데이터로 활용한다는 점에서 공모전 취지와도 맞다.", x: margin, top: y, width: contentWidth)
r.warnIfOverflow(y)
r.endPage()

// Page 2
r.beginPage("2) 서비스 개요")
y = 86
y = r.heading("1. 기획 서비스 소개(한 줄 작성)", top: y)
y = r.drawText("타이밍제주는 TourAPI 관광정보와 제주 버스 데이터를 결합해 렌터카 없이 제주를 여행하는 뚜벅이의 일정 실패를 줄이는 제주 특화 웹·앱 서비스이다. 사용자가 여행 일정을 입력하면 서비스는 관광지 정보, 주변 정류장, 도보 시간, 버스 시간표, 현재 시각을 함께 분석해 출발 권장 시각과 일정 위험도를 알려준다.", x: margin, top: y, width: contentWidth, font: bodyBold, color: blue)
y = r.heading("2. 기획 서비스 주요 기능", top: y)
y = r.drawText("첫 번째 기능은 AI 일정 입력 및 장소 매칭이다. 사용자는 ‘제주공항 도착 후 함덕, 월정리, 성산일출봉을 가고 싶다’처럼 자연어로 입력할 수 있다. 서비스는 입력 문장에서 장소, 시간, 선호 조건을 추출하고, 한국관광공사 TourAPI의 키워드검색과 관광지 정보를 활용해 실제 관광지 후보와 매칭한다. 장소명이 애매하거나 유사한 관광지가 여러 개일 때는 후보를 제시해 사용자가 선택하도록 한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("두 번째 기능은 버스 타이밍 계산이다. 각 관광지 좌표를 기준으로 주변 정류장 후보를 찾고, 정류장까지의 도보 시간과 제주 버스 시간표를 반영해 다음 목적지로 이동 가능한 버스 후보를 계산한다. 서비스는 단순히 ‘몇 번 버스를 타라’고 말하는 데서 끝나지 않고, 관광지에서 언제 출발해야 정류장에 안전하게 도착하는지, 버스를 놓치면 다음 배차까지 어느 정도 기다릴 수 있는지를 함께 보여준다.", x: margin, top: y, width: contentWidth)
y = r.drawText("세 번째 기능은 일정 안전도 점수다. 이 점수는 서비스 구현 가능성 점수가 아니라, 사용자가 입력한 여행 일정이 버스 시간에 얼마나 안전한지를 나타내는 운영 점수다. 경로 성립 여부, 배차 간격, 환승 여유, 정류장까지의 도보 시간, 대체 노선 수, 다음 일정의 여유 시간을 반영해 점수를 계산한다. 점수가 낮으면 ‘월정리 체류 시간을 20분 줄이면 전체 대기 시간이 줄어든다’처럼 이유와 개선안을 함께 제공한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("네 번째 기능은 라이브 여행 모드다. 여행 당일에는 일정표가 고정되어 있으면 안 된다. 사용자가 관광지를 예상보다 빨리 둘러보거나, 식사 시간이 길어지거나, 버스를 놓치면 일정은 계속 바뀐다. 타이밍제주는 현재 시각과 위치를 기준으로 초록·노랑·빨강 상태를 표시해 지금 더 머물러도 되는지, 출발 준비를 해야 하는지, 즉시 정류장으로 이동해야 하는지를 알려준다.", x: margin, top: y, width: contentWidth)
y = r.drawText("다섯 번째 기능은 남는 시간 추천과 AI 재일정이다. 사용자가 예상보다 일찍 관광을 끝내거나 버스를 놓쳐 대기 시간이 생기면, TourAPI 위치기반 관광정보를 활용해 현재 위치에서 남은 시간 안에 실제로 가능한 주변 관광지, 카페, 포토스팟을 추천한다. 반대로 일정이 지연되면 필수 방문지는 유지하고 선택 관광지를 줄이거나 순서를 바꾸는 복구안을 제시한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("알림 기능은 단순히 ‘버스 10분 전’으로 보내지 않는다. 제주 뚜벅이 여행에서는 정류장까지 걸어가는 시간이 중요하므로, 정류장 도보 시간, 안전 버퍼, 버스 도착 예정 시간을 합산해 알림 시점을 정한다. 예를 들어 정류장까지 8분, 안전 버퍼 5분, 버스 도착까지 18분이 남았다면 ‘5분 뒤에는 이동을 시작해야 한다’는 식으로 사용자가 바로 행동할 수 있는 문장으로 알려준다.", x: margin, top: y, width: contentWidth)
r.warnIfOverflow(y)
r.endPage()

// Page 3
r.beginPage("2) 서비스 개요 - 차별성 및 지역 특화")
y = 86
y = r.heading("3. 서비스 차별성", top: y)
y = r.drawText("타이밍제주의 차별성은 ‘추천’보다 ‘운영’에 있다. 지도 앱은 A에서 B까지의 이동 경로를 알려주지만, 하루 전체 일정에서 버스 타이밍과 체류시간을 함께 관리하지는 않는다. 일반 관광 추천 앱은 가볼 만한 장소를 보여주지만, 그 장소가 다음 버스 전까지 실제로 가능한지, 다녀오면 다음 일정이 위험해지는지까지 판단하지는 않는다. 타이밍제주는 관광지 정보와 버스 시간을 결합해 사용자의 하루 일정이 실제로 실행 가능한지 계속 점검한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("또한 일반 AI 여행 플래너와도 다르다. 생성형 AI는 그럴듯한 일정을 만들 수 있지만, 버스 시간이나 환승 가능 여부를 임의로 생성하면 여행 당일에 큰 문제가 된다. 타이밍제주는 OpenAI API를 사용하되, 버스 시간과 경로 가능 여부를 AI가 직접 생성하지 않도록 설계한다. 출발 권장 시각, 일정 안전도, 대기 위험은 서버의 계산 엔진이 처리하고, OpenAI API는 자연어 일정 파싱과 위험 설명, 대체안 설명을 맡는다. 이 구조는 AI 활용성과 데이터 신뢰성을 동시에 확보하기 위한 설계다.", x: margin, top: y, width: contentWidth)
y = r.drawText("사용자 경험 측면에서도 차별화된다. 사용자는 앱을 열어 계속 길찾기를 다시 검색하지 않아도 된다. 관광지에 머무는 동안 서비스가 다음 버스 타이밍을 감시하고, ‘12분 뒤 출발하면 적정’, ‘24분 이상 더 머무르면 다음 버스를 놓칠 가능성이 높음’처럼 행동 가능한 안내를 제공한다. 이는 공모전 기능심사에서도 명확하게 시연할 수 있는 장면이다.", x: margin, top: y, width: contentWidth)
y = r.heading("4. (지역 특화 서비스의 경우 작성 필수) 서비스 내 지역 특화 관련 사항", top: y)
y = r.drawText("타이밍제주는 전국 범용 여행 서비스가 아니라 제주 지역 특화 서비스다. 제주 관광은 관광지 간 거리가 길고, 동서남북 관광권이 뚜렷하며, 렌터카 여부에 따라 여행 경험이 크게 달라진다. 특히 뚜벅이 관광객은 버스 배차와 정류장 위치를 고려하지 않으면 한 번의 지연이 다음 관광지 체류시간과 숙소 도착시간까지 연쇄적으로 영향을 준다.", x: margin, top: y, width: contentWidth)
y = r.drawText("공모전 MVP는 제주 동쪽 대표 관광 코리도어에 집중한다. 제주공항, 함덕해수욕장, 월정리해변, 성산일출봉, 섭지코지, 성산·서귀포 숙소권을 우선 범위로 삼아 실제 동선 기반 시연이 가능하도록 구현한다. 제주 동쪽 코스는 뚜벅이 여행자가 많이 선택하는 동선이면서도 배차와 이동 시간이 일정 운영에 큰 영향을 주는 구간이므로, 문제의 구체성과 서비스 완성도를 동시에 보여주기 적합하다.", x: margin, top: y, width: contentWidth)
y = r.drawText("지역특화 관점에서도 공모전 취지와 맞다. 공고문은 지역 특화 관광서비스에 대한 가점을 언급하고 있으며, 제주 RTO 특별상 가능성도 존재한다. 타이밍제주는 제주 단일 지역의 구체적인 이동 문제에 집중함으로써 ‘전국 어디서나 되는 추천 앱’보다 심사위원에게 명확한 문제 정의와 실행 가능성을 전달할 수 있다.", x: margin, top: y, width: contentWidth)
r.warnIfOverflow(y)
r.endPage()

// Page 4
r.beginPage("3) 데이터 활용 방안")
y = 86
y = r.heading("1. 활용 예정 한국관광공사 OpenAPI", top: y)
y = r.drawText("활용 예정 한국관광공사 OpenAPI는 한국관광공사 국문 관광정보 서비스 TourAPI이다. 이 서비스는 키워드검색, 위치기반 관광정보, 지역기반 관광정보, 이미지정보, 공통정보, 소개정보, 행사정보, 숙박정보 등을 활용해 제주 관광지 후보와 상세 정보를 구성한다. 사용자가 입력한 장소명을 실제 관광지 데이터와 연결하고, 추천 카드에는 관광지명, 주소, 좌표, 대표 이미지, 카테고리, 소개 정보를 표시한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("TourAPI는 타이밍제주에서 단순한 관광지 설명 데이터가 아니라 일정 운영의 출발점으로 사용된다. 사용자가 자연어로 입력한 장소가 실제 관광지인지 확인하고, 좌표를 확보해 주변 정류장 탐색과 버스 시간 계산으로 연결한다. 또한 위치기반 관광정보를 활용해 사용자의 현재 위치 주변 후보를 찾고, 남는 시간 안에 갈 수 있는 관광지·카페·포토스팟을 추천한다.", x: margin, top: y, width: contentWidth)
y = r.heading("2. 데이터 활용 방식", top: y)
y = r.drawText("데이터 활용 방식은 세 단계로 구성한다. 첫째, TourAPI 키워드검색과 지역기반 관광정보를 활용해 제주 지역 관광지 후보를 확보한다. 둘째, TourAPI 위치기반 관광정보를 활용해 현재 위치 주변의 관광지, 음식점, 카페, 행사 후보를 조회한다. 셋째, 이미지정보와 공통·소개정보를 활용해 사용자가 추천 후보를 빠르게 이해하고 선택할 수 있도록 일정 카드와 주변 추천 카드를 구성한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("제주 버스 데이터는 일정 운영의 핵심 계산에 사용한다. 정류소 기본 정보로 관광지 주변 정류장을 찾고, 노선 및 시간표 데이터로 다음 목적지까지 이동 가능한 버스 후보를 계산한다. 정류장까지의 도보 시간, 안전 버퍼, 다음 버스 시간, 놓쳤을 때의 추가 대기 시간을 합산해 출발 권장 시각과 일정 안전도를 만든다. 실시간 도착 정보가 확보되지 않아도 MVP에서는 시간표 기반 안내를 먼저 구현할 수 있고, 이후 실시간 버스 위치나 도착 예정 정보가 확인되면 보정값을 더한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("OpenAI API는 반드시 포함하되, 데이터 신뢰성을 해치지 않는 방식으로 사용한다. OpenAI API는 사용자의 자연어 일정을 장소·시간·취향으로 구조화하고, 서버 계산 결과를 바탕으로 왜 일정이 위험한지, 어떤 대체 일정이 적절한지 설명한다. 반면 버스 시간, 경로 가능 여부, 위험도 점수는 AI가 임의로 생성하지 않고 서버 계산 엔진이 담당한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("이 구조는 구현 가능성 측면에서도 안정적이다. 백엔드는 Spring Boot로 TourAPI, 제주 버스 데이터, OpenAI API를 연결하고, 프론트엔드는 React 기반 프레임워크로 일정 타임라인, 안전도 상태, 추천 카드, 재일정 결과를 보여준다. 핵심 계산을 서버에서 수행하므로 AI 결과의 불확실성을 줄이고, 프론트엔드는 사용자가 즉시 이해할 수 있는 형태로 결과를 시각화한다.", x: margin, top: y, width: contentWidth)
r.warnIfOverflow(y)
r.endPage()

// Page 5
r.beginPage("4) 서비스 발전 방향")
y = 86
y = r.heading("1. 개발 서비스 향후 발전방향", top: y)
y = r.drawText("1단계는 공모전 MVP 완성이다. 개발 기간에는 제주 동쪽 코리도어 중심으로 TourAPI 장소 매칭, 정류장 후보 탐색, 시간표 기반 출발 권장 시각 계산, 일정 안전도 표시, 버스 놓침 시 주변 추천, 일정 지연 시 복구안 제공을 완성한다. 기능심사에서는 사용자가 동쪽 여행 일정을 입력하고, 서비스가 버스 시간에 맞춰 일정을 운영하는 장면을 시연한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("2단계는 제주 전역 확장이다. MVP에서 검증한 데이터 구조를 바탕으로 제주 전역의 정류장, 노선, 시간표 데이터를 확대 적용한다. 처음부터 제주 전역을 모두 다루기보다, 동쪽 코리도어에서 검증한 장소 매칭, 정류장 탐색, 시간표 계산, 일정 안전도 산정 구조를 반복 적용해 안정적으로 확장한다.", x: margin, top: y, width: contentWidth)
y = r.drawText("3단계는 실시간 보정과 다국어 안내다. 버스 위치 또는 도착 예정 정보가 확보되면 시간표 기반 안내에 실시간 보정값을 더해 정확도를 높인다. 외국인 관광객을 위해 한국어 일정 안내를 영어·중국어·일본어로 제공하고, 버스 탑승·하차 알림과 위험 안내도 다국어로 확장한다. 제주를 방문하는 외국인 관광객에게도 대중교통 기반 여행의 접근성을 높일 수 있다.", x: margin, top: y, width: contentWidth)
y = r.drawText("4단계는 RTO 대시보드로의 확장이다. 사용자의 이동 경로와 대기 위험 구간을 분석하면, 어느 관광지 사이에서 대중교통 이동 불편이 큰지, 어느 시간대에 대기 위험이 커지는지 파악할 수 있다. 이는 지역관광 정책, 관광객 분산 방문 유도, 대중교통 기반 관광 활성화에 참고할 수 있는 데이터가 된다.", x: margin, top: y, width: contentWidth)
y = r.drawText("기대효과는 사용자와 지역 모두에 있다. 사용자는 렌터카 없이도 예측 가능한 제주 일정을 운영할 수 있고, 지역은 관광객이 특정 관광지에만 몰리지 않도록 주변 장소와 대체 동선을 추천받는 효과를 얻는다. 또한 공사 OpenAPI는 단순 정보 조회가 아니라 실제 여행 의사결정의 핵심 데이터로 활용된다.", x: margin, top: y, width: contentWidth)
y = r.drawText("서비스 고도화 과정에서는 사용자 체류시간 학습도 가능하다. 실제 사용자가 특정 관광지에 얼마나 머무는지 축적되면, 사진형 사용자, 카페형 사용자, 빠른 관광형 사용자처럼 유형별 체류시간을 더 정교하게 예측할 수 있다. 이후에는 ‘비슷한 여행자들은 이 장소에서 평균 50분 머물렀다’는 식으로 일정 안전도 계산의 정확도를 높일 수 있다.", x: margin, top: y, width: contentWidth)
y = r.drawText("결과적으로 타이밍제주는 제주 뚜벅이 여행자의 개인 문제를 해결하는 동시에, TourAPI 관광정보 활용도를 높이고 지역 관광 운영 데이터로 발전할 수 있는 서비스다. 공모전 단계에서는 작고 명확한 MVP로 완성도를 보여주고, 이후 제주 전역과 실시간 교통 데이터, 다국어 안내, 지역관광 대시보드로 확장해 지속 가능한 관광 서비스로 성장시키는 것을 목표로 한다.", x: margin, top: y, width: contentWidth)
r.warnIfOverflow(y)
r.endPage()

r.save(to: outputPath)
