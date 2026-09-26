import { useEffect, useState } from "react";

/**
 * Returns `value` only after it has stopped changing for `delayMs`.
 *
 * Used by the filter inputs on the explorer/audit pages, which previously issued
 * one API request per keystroke because the effect depended directly on the raw
 * filter state.
 */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    // Clearing on every change is what makes this a debounce rather than a
    // sequence of independent delayed updates.
    return () => clearTimeout(timer);
  }, [value, delayMs]);

  return debounced;
}
