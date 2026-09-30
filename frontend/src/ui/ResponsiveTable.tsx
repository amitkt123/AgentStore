import styles from "./ResponsiveTable.module.css";

export interface Column<T> { key: string; header: string; render: (row: T) => React.ReactNode }

export function ResponsiveTable<T>({ columns, rows, rowKey, empty }: { columns: Column<T>[]; rows: T[]; rowKey: (r: T) => string; empty: React.ReactNode }) {
  if (rows.length === 0) return <>{empty}</>;
  return (
    <table className={styles.table}>
      <thead>
        <tr>{columns.map((c) => <th key={c.key} scope="col">{c.header}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={rowKey(r)}>
            {columns.map((c) => <td key={c.key} data-label={c.header}>{c.render(r)}</td>)}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
