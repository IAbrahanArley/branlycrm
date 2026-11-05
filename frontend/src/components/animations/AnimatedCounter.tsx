"use client";
import { useEffect, useRef, useState } from "react";
export function AnimatedCounter({
  value,
  format,
}: {
  value: number;
  format: (value: number) => string;
}) {
  const [display, setDisplay] = useState(value);
  const previous = useRef(value);
  useEffect(() => {
    const from = previous.current;
    previous.current = value;
    let frame = 0;
    let start: number | undefined;
    const duration = window.matchMedia("(prefers-reduced-motion: reduce)")
      .matches
      ? 0
      : 420;
    function tick(now: number) {
      start ??= now;
      const progress =
        duration === 0 ? 1 : Math.min(1, (now - start) / duration);
      setDisplay(from + (value - from) * (1 - Math.pow(1 - progress, 3)));
      if (progress < 1) frame = requestAnimationFrame(tick);
    }
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [value]);
  return (
    <>
      <span aria-hidden="true">{format(Math.round(display))}</span>
      <span className="sr-only">{format(value)}</span>
    </>
  );
}
