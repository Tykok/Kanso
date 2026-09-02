import { ProjectPageView } from "@/components/views/project-page";

/**
 * Screen 05. A project's id and not its name: two teams may both own a project called
 * `Onboarding`, which is the same reason `labels` are team-scoped.
 */
export default async function ProjectRoute({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <ProjectPageView projectId={id} />;
}
