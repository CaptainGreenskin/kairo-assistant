import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { cronApi, skillsApi } from "../api/client";
import type {
  CreateCronTaskRequest,
  EditCronTaskRequest,
} from "../api/types";

const TASKS_KEY = ["cron", "tasks"] as const;

export function useCronTasks() {
  return useQuery({
    queryKey: TASKS_KEY,
    queryFn: cronApi.list,
  });
}

export function useSkills() {
  return useQuery({
    queryKey: ["skills"],
    queryFn: skillsApi.list,
    staleTime: 60_000,
  });
}

/** All mutations invalidate the task list so the table re-renders post-change. */
function invalidateTasks(client: ReturnType<typeof useQueryClient>) {
  client.invalidateQueries({ queryKey: TASKS_KEY });
}

export function useCreateTask() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateCronTaskRequest) => cronApi.create(body),
    onSuccess: (t) => {
      toast.success(`Created task ${t.id}`);
      invalidateTasks(client);
    },
    onError: (e: Error) => toast.error(`Create failed: ${e.message}`),
  });
}

export function useEditTask() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: EditCronTaskRequest }) =>
      cronApi.edit(id, body),
    onSuccess: (t) => {
      toast.success(`Updated ${t.id}`);
      invalidateTasks(client);
    },
    onError: (e: Error) => toast.error(`Edit failed: ${e.message}`),
  });
}

export function usePauseTask() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => cronApi.pause(id),
    onSuccess: (t) => {
      toast.success(`Paused ${t.id}`);
      invalidateTasks(client);
    },
    onError: (e: Error) => toast.error(`Pause failed: ${e.message}`),
  });
}

export function useResumeTask() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => cronApi.resume(id),
    onSuccess: (t) => {
      toast.success(`Resumed ${t.id}`);
      invalidateTasks(client);
    },
    onError: (e: Error) => toast.error(`Resume failed: ${e.message}`),
  });
}

export function useTriggerTask() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => cronApi.trigger(id),
    onSuccess: (r) => {
      toast.success(`Triggered ${r.id ?? "task"}`);
      invalidateTasks(client);
    },
    onError: (e: Error) => toast.error(`Trigger failed: ${e.message}`),
  });
}

export function useDeleteTask() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => cronApi.delete(id),
    onSuccess: (r) => {
      toast.success(`Deleted ${r.id ?? "task"}`);
      invalidateTasks(client);
    },
    onError: (e: Error) => toast.error(`Delete failed: ${e.message}`),
  });
}
