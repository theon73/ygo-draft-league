export default function Placeholder({ title, phase }: { title: string; phase?: number }) {
  return (
    <section>
      <h2>{title}</h2>
      <p>{phase ? `Coming in Phase ${phase} — see PLANNING.md.` : "Nothing here."}</p>
    </section>
  );
}
