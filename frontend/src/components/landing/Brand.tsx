import Image from "next/image";
export function Brand({ light = false }: { light?: boolean }) {
  return (
    <a
      className={`brand ${light ? "brand-light" : ""}`}
      href="#inicio"
      aria-label="Branly — início"
    >
      <Image
        src="/brand/branly-original.png"
        width={500}
        height={500}
        alt="Branly"
        unoptimized
        priority={!light}
      />
    </a>
  );
}
