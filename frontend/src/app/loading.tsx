export default function Loading() {
  return (
    <div className="container section" role="status">
      <div className="skeleton skeleton-title" />
      <p>Finding your next place…</p>
      <div className="listing-grid">
        {[1, 2, 3].map((value) => (
          <div key={value} className="skeleton skeleton-card" />
        ))}
      </div>
    </div>
  );
}
