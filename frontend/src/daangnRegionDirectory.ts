export type DirectoryRegion = {
  id: string
  name: string
  province: string
  district: string
  neighborhood: string
}
const aliases: Record<string, string> = {
  서울특별시: '서울',
  부산광역시: '부산',
  대구광역시: '대구',
  인천광역시: '인천',
  광주광역시: '광주',
  대전광역시: '대전',
  울산광역시: '울산',
  세종특별자치시: '세종',
  경기도: '경기',
  강원특별자치도: '강원',
  충청북도: '충북',
  충청남도: '충남',
  전북특별자치도: '전북',
  전라남도: '전남',
  경상북도: '경북',
  경상남도: '경남',
  제주특별자치도: '제주',
  강원도: '강원',
  전라북도: '전북',
  전남광주통합특별시: '전남|광주|전라남도|광주광역시',
}
type IndexedRegion = DirectoryRegion & { searchText: string }
let cached: Promise<IndexedRegion[]> | null = null
export function loadRegionDirectory(): Promise<IndexedRegion[]> {
  if (!cached)
    cached = (async () => {
      const response = await fetch(`${import.meta.env.BASE_URL}data/daangn-regions.json`, {
        cache: 'no-cache',
        signal: AbortSignal.timeout(15000),
      })
      if (!response.ok)
        throw new Error(
          '지역 목록이 준비되지 않았습니다. 인기 동네 또는 선택한 동네 주변 조회를 이용해 주세요.',
        )
      const data: unknown = await response.json()
      if (
        !data ||
        typeof data !== 'object' ||
        !('regions' in data) ||
        !Array.isArray(data.regions) ||
        data.regions.length > 100000
      )
        throw new Error('지역 목록 형식을 확인하지 못했습니다.')
      const seen = new Set<string>(),
        regions: IndexedRegion[] = []
      for (const row of data.regions) {
        if (
          !row ||
          typeof row.id !== 'string' ||
          !/^\d{1,20}$/.test(row.id) ||
          seen.has(row.id) ||
          ![row.name, row.province, row.district, row.neighborhood].every(
            (value) => typeof value === 'string' && value.length <= 200,
          ) ||
          !row.province ||
          !row.neighborhood
        )
          continue
        seen.add(row.id)
        const region: DirectoryRegion = {
          id: row.id,
          name: row.name,
          province: row.province,
          district: row.district,
          neighborhood: row.neighborhood,
        }
        const alternateAddresses = (aliases[row.province] ?? row.province)
          .split('|')
          .map((province) => `${province}${row.district}${row.neighborhood}`)
        regions.push({
          ...region,
          searchText: [row.name, ...alternateAddresses]
            .join(' ')
            .replace(/\s+/g, '')
            .toLocaleLowerCase(),
        })
      }
      if (!regions.length)
        throw new Error('선택할 동네가 없습니다. 지역 목록을 다시 수집해 주세요.')
      return regions
    })().catch((error) => {
      cached = null
      throw error instanceof SyntaxError
        ? new Error('지역 목록 파일이 없습니다. 인기 동네를 이용하거나 지역 목록을 준비해 주세요.')
        : error
    })
  return cached
}
export function filterRegionDirectory(regions: IndexedRegion[], query: string) {
  const terms = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  if (!terms.length) return []
  return regions.filter((region) => terms.every((term) => region.searchText.includes(term)))
}

function regionAddress(value: string) {
  const parts = value.trim().split(/\s+/)
  if (parts.length) parts[0] = aliases[parts[0]] ?? parts[0]
  return parts.join('').toLocaleLowerCase()
}
/** All selection sources use the IDs in the imported daangn-regions.json. */
export async function resolveDirectoryRegion(candidate: {
  id: string
  name: string
}): Promise<DirectoryRegion> {
  const regions = await loadRegionDirectory()
  const address = regionAddress(candidate.name)
  const matches = regions.filter((region) => regionAddress(region.name) === address)
  if (matches.length === 1) return matches[0]
  // A short or ambiguous name must not be resolved using an unrelated API ID.
  throw new Error(
    '지역 목록에서 해당 주소를 확인하지 못했습니다. 지역명을 검색해 전체 주소를 선택해 주세요.',
  )
}
