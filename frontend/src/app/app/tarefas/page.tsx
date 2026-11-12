import type { Metadata } from "next";
import { TaskWorkspace } from "@/components/tasks/task-workspace";
import { taskTabs, type TaskTab } from "@/lib/api/tasks";

export const metadata: Metadata = { title: "Tarefas | Branly CRM" };

export default async function TasksPage({ searchParams }: { searchParams: Promise<{ filtro?: string | string[] }> }) {
  const { filtro } = await searchParams;
  const tab = typeof filtro === "string" && Object.hasOwn(taskTabs, filtro) ? filtro as TaskTab : "TODAY";
  return <TaskWorkspace key={tab} initialTab={tab} />;
}
