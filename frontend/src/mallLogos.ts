import coupang from './logos/쿠팡.png'
import gmarket from './logos/지마켓.png'
import auction from './logos/옥션.png'
import elevenStreet from './logos/11번가.png'
import naver from './logos/네이버쇼핑.png'
import ssg from './logos/ssg.png'
import himart from './logos/롯데마켓.png'
import interpark from './logos/인터파크.png'
import tmon from './logos/티몬.png'
import wemakeprice from './logos/위메프.png'
import amazon from './logos/아마존.png'
import ebay from './logos/이베이.png'
import danawa from './logos/다나와.png'
import gsFresh from './logos/gs플레쉬몰.png'
import g9 from './logos/g9.png'

// Match known names only; seller URLs may point to Danawa redirects.
const logos: Record<string, string> = {}
for (const [logo, names] of [
  [coupang, ['쿠팡', 'coupang']],
  [gmarket, ['지마켓', 'G마켓', 'Gmarket', 'G마켓글로벌']],
  [auction, ['옥션', 'auction']],
  [elevenStreet, ['11번가', '11st', '11street']],
  [naver, ['네이버쇼핑', 'NAVER쇼핑', 'navershopping', '네이버스마트스토어', '스마트스토어']],
  [ssg, ['SSG', 'SSG.COM', 'SSG닷컴', '쓱닷컴']],
  // Despite its filename, this asset is the LOTTE HIMART wordmark.
  [himart, ['하이마트', '롯데하이마트', '하이마트쇼핑몰', 'LOTTEHIMART', 'HIMART']],
  [interpark, ['인터파크', 'interpark', '인터파크쇼핑']],
  [tmon, ['티몬', '티켓몬스터', 'tmon']],
  [wemakeprice, ['위메프', 'wemakeprice']],
  [amazon, ['아마존', 'amazon', 'amazon.com']],
  [ebay, ['이베이', 'ebay', 'ebay.com']],
  [danawa, ['다나와', 'danawa']],
  [gsFresh, ['GS프레시몰', 'GS프레쉬몰', 'GS플레쉬몰', 'GS프레시', 'GSfresh', 'GSfresh몰']],
  [g9, ['G9', '지구']],
] as [string, string[]][]) {
  for (const name of names) logos[normalize(name)] = logo
}

function normalize(name: string) {
  return name.normalize('NFKC').replace(/[\s._-]/g, '').toLowerCase()
}

export function mallLogo(name: string): string | undefined {
  return logos[normalize(name)]
}
