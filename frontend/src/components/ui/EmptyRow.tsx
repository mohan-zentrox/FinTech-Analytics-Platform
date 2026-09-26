/** Consistent "nothing here" / "loading" row for the data tables. */
export default function EmptyRow({ colSpan, message }: { colSpan: number; message: string }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-4 py-6 text-center text-slate-500">
        {message}
      </td>
    </tr>
  );
}
