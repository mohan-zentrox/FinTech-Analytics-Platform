/** Shared previous/next pager used by the transaction, alert, report and audit tables. */
export default function Pagination({
  page,
  totalPages,
  onChange,
  disabled = false,
}: {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
  disabled?: boolean;
}) {
  return (
    <div className="flex items-center justify-between text-sm text-slate-600">
      <span>
        Page {page + 1} of {Math.max(totalPages, 1)}
      </span>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={disabled || page === 0}
          onClick={() => onChange(page - 1)}
          className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
        >
          Previous
        </button>
        <button
          type="button"
          disabled={disabled || page + 1 >= totalPages}
          onClick={() => onChange(page + 1)}
          className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
        >
          Next
        </button>
      </div>
    </div>
  );
}
