export function RelativeTime({ iso }: { iso?: string }) {
  if (!iso) return <span className="text-text-dim">—</span>;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return <span>{iso}</span>;
  const delta = (date.getTime() - Date.now()) / 1000;
  return (
    <span title={date.toLocaleString()}>
      {humanise(delta)}
    </span>
  );
}

function humanise(seconds: number): string {
  const abs = Math.abs(seconds);
  const suffix = seconds >= 0 ? "from now" : "ago";
  if (abs < 60) return `${Math.round(abs)} s ${suffix}`;
  if (abs < 3600) return `${Math.round(abs / 60)} min ${suffix}`;
  if (abs < 86_400) return `${Math.round(abs / 3600)} h ${suffix}`;
  return `${Math.round(abs / 86_400)} d ${suffix}`;
}
