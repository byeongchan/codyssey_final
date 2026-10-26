export default function Loading({ label = '데이터를 불러오는 중입니다', className = '' }: { label?: string; className?: string }) {
  return <div role="status" className={`flex min-h-20 items-center justify-center gap-3 py-5 ${className}`}>
    <span aria-hidden="true" className="prizm-loading-spinner"/>
    <span className="sr-only">{label}</span>
  </div>
}
