"use client";
import { useRef, type ReactNode } from "react";
export function ParallaxElement({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  return (
    <div
      className="dashboard-perspective"
      ref={ref}
      onPointerMove={(event) => {
        if (
          !window.matchMedia(
            "(hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)",
          ).matches
        )
          return;
        const rect = event.currentTarget.getBoundingClientRect();
        const x = (event.clientX - rect.left) / rect.width - 0.5;
        const y = (event.clientY - rect.top) / rect.height - 0.5;
        ref.current?.style.setProperty("--tilt-x", `${-y * 2}deg`);
        ref.current?.style.setProperty("--tilt-y", `${x * 2}deg`);
      }}
      onPointerLeave={() => {
        ref.current?.style.setProperty("--tilt-x", "0deg");
        ref.current?.style.setProperty("--tilt-y", "0deg");
      }}
    >
      {children}
    </div>
  );
}
