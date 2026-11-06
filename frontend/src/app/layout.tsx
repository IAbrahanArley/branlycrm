import type { Metadata } from "next";
import localFont from "next/font/local";
import "./globals.css";
const redHat = localFont({
  src: "./fonts/RedHatDisplay.ttf",
  variable: "--font-branly",
  display: "swap",
  weight: "300 900",
});
export const metadata: Metadata = {
  title: "Branly | CRM para clínicas de estética",
  description:
    "Explore a proposta do Branly para clínicas de estética: demonstração interativa do CRM e simulação de cenários comerciais. Produto em desenvolvimento.",
  openGraph: {
    title: "Branly | CRM para clínicas de estética",
    description:
      "Explore uma demonstração conceitual e conheça a proposta do Branly.",
    siteName: "Branly",
    locale: "pt_BR",
    type: "website",
  },
};
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="pt-BR" className={redHat.variable}>
      <body>{children}</body>
    </html>
  );
}
