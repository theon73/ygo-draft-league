import { NavLink, Route, Routes } from "react-router-dom";
import Home from "./pages/Home";
import Placeholder from "./pages/Placeholder";
import AccountMenu from "./auth/AccountMenu";
import Explore from "./pages/explore/Explore";
import CardView from "./pages/explore/CardView";
import BanListView from "./pages/explore/BanListView";

const NAV = [
  { to: "/", label: "Home" },
  { to: "/standings", label: "Standings" },
  { to: "/rosters", label: "Rosters" },
  { to: "/draft", label: "Draft" },
  { to: "/transactions", label: "Transactions" },
  { to: "/explore", label: "Explorer" },
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
        <AccountMenu />
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/standings" element={<Placeholder title="Standings" phase={4} />} />
          <Route path="/rosters" element={<Placeholder title="Rosters" phase={4} />} />
          <Route path="/draft" element={<Placeholder title="Draft Room" phase={3} />} />
          <Route path="/transactions" element={<Placeholder title="Transactions" phase={4} />} />
          <Route path="/explore" element={<Explore />} />
          <Route path="/explore/cards/:id" element={<CardView />} />
          <Route path="/explore/banlists/:id" element={<BanListView />} />
          <Route path="*" element={<Placeholder title="Not found" />} />
        </Routes>
      </main>
    </div>
  );
}
