import type { ReactNode } from "react"

export type IconName = "bell" | "search" | "sparkles" | "moon" | "sun" | "user" | "heart" | "menu" | "home" | "laptop" | "headphones" | "watch" | "camera" | "kitchen" | "shirt" | "baby" | "car" | "arrow" | "trend" | "check" | "send" | "chevron" | "star" | "clock" | "shield" | "external"

export function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  const common = {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.8,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    "aria-hidden": true as const,
  }

  const paths: Record<IconName, ReactNode> = {
    bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9Z"/><path d="M10 21h4"/></>,
    search: (
      <>
        <circle cx="11" cy="11" r="7" />
        <path d="m20 20-4-4" />
      </>
    ),
    sparkles: (
      <>
        <path d="m12 3 1.2 3.8L17 8l-3.8 1.2L12 13l-1.2-3.8L7 8l3.8-1.2L12 3Z" />
        <path d="m19 14 .7 2.3L22 17l-2.3.7L19 20l-.7-2.3L16 17l2.3-.7L19 14Z" />
        <path d="m5 13 .7 2.3L8 16l-2.3.7L5 19l-.7-2.3L2 16l2.3-.7L5 13Z" />
      </>
    ),
    moon: <path d="M20 16.5A8.5 8.5 0 0 1 7.5 4 8.5 8.5 0 1 0 20 16.5Z" />,
    sun: (
      <>
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.65 17.65l1.42 1.42M2 12h2M20 12h2M4.93 19.07l1.42-1.42M17.65 6.35l1.42-1.42" />
      </>
    ),
    user: (
      <>
        <circle cx="12" cy="8" r="4" />
        <path d="M4 21a8 8 0 0 1 16 0" />
      </>
    ),
    heart: (
      <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8Z" />
    ),
    menu: (
      <>
        <path d="M4 6h16M4 12h16M4 18h16" />
      </>
    ),
    home: (
      <>
        <path d="m3 11 9-8 9 8" />
        <path d="M5 10v11h14V10M9 21v-7h6v7" />
      </>
    ),
    laptop: (
      <>
        <rect x="4" y="4" width="16" height="11" rx="1" />
        <path d="M2 19h20M8 19l1-4h6l1 4" />
      </>
    ),
    headphones: (
      <>
        <path d="M4 14v-2a8 8 0 0 1 16 0v2" />
        <path d="M18 19h-2v-7h4v5a2 2 0 0 1-2 2ZM6 19H4v-5a2 2 0 0 1 2-2h2v7H6Z" />
      </>
    ),
    watch: (
      <>
        <rect x="7" y="6" width="10" height="12" rx="3" />
        <path d="m9 6 1-4h4l1 4M9 18l1 4h4l1-4" />
      </>
    ),
    camera: (
      <>
        <path d="M4 7h4l2-2h4l2 2h4v12H4V7Z" />
        <circle cx="12" cy="13" r="3.5" />
      </>
    ),
    kitchen: (
      <>
        <path d="M8 3v18M5 3v5a3 3 0 0 0 6 0V3M17 3v18M17 3a4 4 0 0 1 4 4v5h-4" />
      </>
    ),
    shirt: <path d="m8 4-5 3 3 5 2-1v10h8V11l2 1 3-5-5-3a4 4 0 0 1-8 0Z" />,
    baby: (
      <>
        <circle cx="12" cy="13" r="8" />
        <path d="M10 5c0-2 3-3 4-1 .5 1-.4 2-1.5 2M9 13h.01M15 13h.01M9.5 16a4 4 0 0 0 5 0" />
      </>
    ),
    car: (
      <>
        <path d="m4 16 1.5-7h13l1.5 7" />
        <path d="M3 16h18v4h-3v-2H6v2H3v-4ZM7 13h.01M17 13h.01" />
      </>
    ),
    arrow: (
      <>
        <path d="M5 12h14M14 7l5 5-5 5" />
      </>
    ),
    trend: (
      <>
        <path d="m3 17 6-6 4 4 8-8" />
        <path d="M15 7h6v6" />
      </>
    ),
    check: <path d="m5 12 4 4L19 6" />,
    send: (
      <>
        <path d="m22 2-7 20-4-9-9-4 20-7Z" />
        <path d="M22 2 11 13" />
      </>
    ),
    chevron: <path d="m9 18 6-6-6-6" />,
    star: (
      <path d="m12 2.5 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2-4.5-4.4 6.2-.9L12 2.5Z" />
    ),
    clock: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3 2" />
      </>
    ),
    shield: (
      <>
        <path d="M12 22s8-3 8-10V5l-8-3-8 3v7c0 7 8 10 8 10Z" />
        <path d="m9 12 2 2 4-4" />
      </>
    ),
    external: (
      <>
        <path d="M14 4h6v6M20 4l-9 9" />
        <path d="M18 13v6H5V6h6" />
      </>
    ),
  }

  return <svg {...common}>{paths[name]}</svg>
}

export function PrizmMark({ size = 34 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 36 36"
      fill="none"
      aria-hidden="true"
      className="prizm-mark"
    >
      <path d="M18 3.5 32 28H4L18 3.5Z" fill="currentColor" opacity=".18" />
      <path d="M18 3.5 18 28H4L18 3.5Z" fill="currentColor" opacity=".75" />
      <path d="M18 3.5 32 28H18V3.5Z" fill="currentColor" />
      <path d="m18 12 5 8.8H18V12Z" fill="white" opacity=".92" />
      <path
        d="M8 31.5h20"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        opacity=".45"
      />
    </svg>
  )
}



