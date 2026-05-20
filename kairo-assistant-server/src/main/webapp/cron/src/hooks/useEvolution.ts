import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { evolutionApi } from "../api/evolution";

const SKILLS_KEY = ["evolution", "skills"];

export function useEvolutionSkills() {
  return useQuery({
    queryKey: SKILLS_KEY,
    queryFn: evolutionApi.listSkills,
    refetchInterval: 8000,
  });
}

export function usePinSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => evolutionApi.pin(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: SKILLS_KEY }),
  });
}

export function useUnpinSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => evolutionApi.unpin(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: SKILLS_KEY }),
  });
}

export function useArchiveSkill() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => evolutionApi.archive(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: SKILLS_KEY }),
  });
}

export function useRunCurator() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (dry: boolean) => evolutionApi.runCurator(dry),
    onSuccess: () => qc.invalidateQueries({ queryKey: SKILLS_KEY }),
  });
}

export function useRunLifecycle() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => evolutionApi.runLifecycle(),
    onSuccess: () => qc.invalidateQueries({ queryKey: SKILLS_KEY }),
  });
}
