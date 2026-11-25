import { NavLink, Route, Routes } from "react-router-dom";
import Home from "./pages/Home";
import Players from "./pages/Players";
import Placeholder from "./pages/Placeholder";

const NAV = [
  { to: "/", label: "Home" },
  { to: "/players", label: "Players" },
  { to: "/standings", label: "Standings" },
  { to: "/rosters", label: "Rosters" },
  { to: "/draft", label: "Draft" },
  { to: "/transactions", label: "Transactions" },
];

export default function App() {
  return (
    <div className="layout">
      <header>
        <h1>YGO Draft League</h1>
        <nav>
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.to === "/"}>
              {n.label}
            </NavLink>
          ))}
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/players" element={<Players />} />
          <Route path="/standings" element={<Placeholder title="Standings" phase={4} />} />
          <Route path="/rosters" element={<Placeholder title="Rosters" phase={4} />} />
          <Route path="/draft" element={<Placeholder title="Draft Room" phase={3} />} />
          <Route path="/transactions" element={<Placeholder title="Transactions" phase={4} />} />
          <Route path="*" element={<Placeholder title="Not found" />} />
        </Routes>
      </main>
    </div>
  );
}
