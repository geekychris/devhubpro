/**
 * Tiny 5-star rating widget. Click a star to set; click the same star (or rating==value) to clear.
 * Hover reveals the would-be value. Renders empty stars when `value` is null.
 */
import { useState } from 'react';

export function StarRating({
  value,
  onChange,
  readOnly = false,
  size = 18,
}: {
  value: number | null;
  onChange?: (next: number | null) => void;
  readOnly?: boolean;
  size?: number;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const display = hover ?? value ?? 0;

  return (
    <div
      className={`inline-flex items-center gap-0.5 ${readOnly ? '' : 'cursor-pointer'}`}
      onMouseLeave={() => setHover(null)}
      role={readOnly ? undefined : 'radiogroup'}
      aria-label="Rating"
    >
      {[1, 2, 3, 4, 5].map((n) => {
        const active = n <= display;
        return (
          <button
            key={n}
            type="button"
            disabled={readOnly}
            onMouseEnter={() => !readOnly && setHover(n)}
            onClick={() => {
              if (readOnly || !onChange) return;
              onChange(value === n ? null : n);
            }}
            className="bg-transparent border-0 p-0 leading-none focus:outline-none disabled:cursor-default"
            title={readOnly ? `${value ?? 0}/5` : `Rate ${n}/5`}
            style={{ fontSize: size, lineHeight: 1 }}
          >
            <span style={{ color: active ? '#f59e0b' : '#d1d5db' }}>★</span>
          </button>
        );
      })}
      {!readOnly && (value ?? 0) > 0 && (
        <button
          type="button"
          onClick={() => onChange?.(null)}
          className="ml-1 text-[11px] text-gray-500 hover:text-gray-700"
          title="Clear rating"
        >
          clear
        </button>
      )}
    </div>
  );
}
