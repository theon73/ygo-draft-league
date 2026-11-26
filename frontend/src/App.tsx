import { NavLink, Route, Routes } from "react-router-dom";
import Home from "./pages/Home";
import Placeholder from "./pages/Placeholder";
import AccountMenu from "./auth/AccountMenu";
import Explore from "./pages/explore/Explore";
import CardView from "./pages/explore/CardView";
import BanListView from "./pages/explore/BanListView";
import MyLeagues from "./pages/leagues/MyLeagues";
import LeagueLayout from "./pages/leagues/LeagueLayout";
import LeagueHome from "./pages/leagues/LeagueHome";
import MembersPage from "./pages/leagues/MembersPage";
import SeasonsPage from "./pages/leagues/SeasonsPage";
import LeagueSettings from "./pages/leagues/LeagueSettings";
import SeasonLayout from "./pages/leagues/SeasonLayout";
import SeasonSetup from "./pages/leagues/SeasonSetup";
import InvitePage, { PendingInviteRedirect } from "./pages/InvitePage";

const NAV = [
  { to: "/", label: "Home" },
  { to: "/leagues", label: "Leagues" },
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
        <PendingInviteRedirect />
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/leagues" element={<MyLeagues />} />
          {/* League pages share LeagueLayout (loads the league, shows the tabs); children render inside it. */}
          <Route path="/leagues/:leagueId" element={<LeagueLayout />}>
            <Route index element={<LeagueHome />} />
            <Route path="members" element={<MembersPage />} />
            <Route path="seasons" element={<SeasonsPage />} />
            <Route path="settings" element={<LeagueSettings />} />
            {/* A season's own pages (setup, and later draft, rosters, standings, transactions). */}
            <Route path="seasons/:seasonId" element={<SeasonLayout />}>
              <Route index element={<SeasonSetup />} />
              <Route path="draft" element={<Placeholder title="Draft Room" phase={3} />} />
              <Route path="rosters" element={<Placeholder title="Rosters" phase={4} />} />
              <Route path="standings" element={<Placeholder title="Standings" phase={4} />} />
              <Route path="transactions" element={<Placeholder title="Transactions" phase={4} />} />
            </Route>
          </Route>
          <Route path="/explore" element={<Explore />} />
          <Route path="/explore/cards/:id" element={<CardView />} />
          <Route path="/explore/banlists/:id" element={<BanListView />} />
          <Route path="/invite/:code" element={<InvitePage />} />
          <Route path="*" element={<Placeholder title="Not found" />} />
        </Routes>
      </main>
    </div>
  );
}
