import NavBar from "./NavBar";
import Footer from "./Footer";

export default function PageShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="page page-ready page-decorated">
      <NavBar />
      <main className="content">{children}</main>
      <Footer />
    </div>
  );
}
