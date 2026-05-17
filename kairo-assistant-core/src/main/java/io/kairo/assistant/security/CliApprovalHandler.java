package io.kairo.assistant.security;

import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tool.ToolCallRequest;
import io.kairo.api.tool.UserApprovalHandler;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CliApprovalHandler implements UserApprovalHandler {

    private final Scanner scanner;
    private final PrintStream out;
    private final Set<String> sessionApprovedTools = new HashSet<>();

    public CliApprovalHandler() {
        this(new Scanner(System.in), System.out);
    }

    public CliApprovalHandler(Scanner scanner, PrintStream out) {
        this.scanner = scanner;
        this.out = out;
    }

    @Override
    public Mono<ApprovalResult> requestApproval(ToolCallRequest request) {
        if (sessionApprovedTools.contains(request.toolName())) {
            return Mono.just(ApprovalResult.allow());
        }

        return Mono.fromCallable(() -> promptUser(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ApprovalResult promptUser(ToolCallRequest request) {
        out.println();
        out.println("=== Tool Approval Required ===");
        out.printf("  Tool:        %s%n", request.toolName());
        out.printf("  Side Effect: %s%n", request.sideEffect());
        if (request.args() != null && !request.args().isEmpty()) {
            out.printf("  Args:        %s%n", formatArgs(request));
        }
        out.println();
        out.print("Allow? [y]es / [n]o / [a]lways for this tool: ");
        out.flush();

        if (!scanner.hasNextLine()) {
            return ApprovalResult.denied("No input available");
        }

        String input = scanner.nextLine().trim().toLowerCase();
        return switch (input) {
            case "y", "yes" -> ApprovalResult.allow();
            case "a", "always" -> {
                sessionApprovedTools.add(request.toolName());
                yield ApprovalResult.allow();
            }
            default -> ApprovalResult.denied("User denied tool: " + request.toolName());
        };
    }

    public void resetSessionApprovals() {
        sessionApprovedTools.clear();
    }

    private String formatArgs(ToolCallRequest request) {
        var args = request.args();
        if (args.size() <= 3) return args.toString();

        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (var entry : args.entrySet()) {
            if (i >= 3) {
                sb.append(", ...(").append(args.size() - 3).append(" more)");
                break;
            }
            if (i > 0) sb.append(", ");
            String val = String.valueOf(entry.getValue());
            if (val.length() > 80) val = val.substring(0, 77) + "...";
            sb.append(entry.getKey()).append("=").append(val);
            i++;
        }
        sb.append("}");
        return sb.toString();
    }
}
