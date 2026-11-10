import type { Metadata } from "next";
import { TeamWorkspace } from "@/components/team/team-workspace";

export const metadata: Metadata = { title: "Equipe e convites | Branly CRM" };

export default function TeamPage() {
  return <TeamWorkspace />;
}
