package io.kairo.assistant.server.web;

import io.kairo.assistant.gateway.SessionKey;
import io.kairo.assistant.gateway.UnifiedGateway;
import io.kairo.assistant.goal.Goal;
import io.kairo.assistant.goal.GoalStore;
import io.kairo.assistant.security.UserPairing;
import io.kairo.assistant.server.OutboundMessageRouter;
import io.kairo.assistant.server.SessionMirror;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final UnifiedGateway gateway;
    private final GoalStore goalStore;
    private final OutboundMessageRouter outboundRouter;
    private final UserPairing userPairing;
    private final SessionMirror sessionMirror;

    public DashboardController(UnifiedGateway gateway, GoalStore goalStore,
                               OutboundMessageRouter outboundRouter,
                               UserPairing userPairing, SessionMirror sessionMirror) {
        this.gateway = gateway;
        this.goalStore = goalStore;
        this.outboundRouter = outboundRouter;
        this.userPairing = userPairing;
        this.sessionMirror = sessionMirror;
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeSessions", gateway.activeRequestCount());
        data.put("totalGoals", goalStore.all().size());
        data.put("activeGoals", goalStore.active().size());
        data.put("registeredPlatforms", outboundRouter.platforms());
        data.put("pairingEnabled", userPairing.isEnabled());
        data.put("pairedUsers", userPairing.allUsers().size());
        data.put("mirrorListeners", sessionMirror.subscriberCount());
        data.put("isDraining", gateway.isDraining());
        data.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(data);
    }

    @GetMapping("/goals")
    public ResponseEntity<List<Goal>> listGoals() {
        return ResponseEntity.ok(goalStore.all());
    }

    @PostMapping("/goals/{id}/pause")
    public ResponseEntity<Map<String, String>> pauseGoal(@PathVariable String id) {
        return goalStore.get(id).map(goal -> {
            goalStore.update(goal.withStatus(Goal.GoalStatus.PAUSED));
            return ResponseEntity.ok(Map.of("status", "paused", "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/goals/{id}/resume")
    public ResponseEntity<Map<String, String>> resumeGoal(@PathVariable String id) {
        return goalStore.get(id).map(goal -> {
            goalStore.update(goal.withStatus(Goal.GoalStatus.ACTIVE));
            return ResponseEntity.ok(Map.of("status", "active", "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/goals/{id}")
    public ResponseEntity<Map<String, String>> deleteGoal(@PathVariable String id) {
        boolean deleted = goalStore.delete(id);
        if (deleted) {
            return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/platforms")
    public ResponseEntity<Map<String, Object>> platforms() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registered", outboundRouter.platforms());
        data.put("count", outboundRouter.registeredPlatforms().size());
        return ResponseEntity.ok(data);
    }

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> users() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", userPairing.isEnabled());
        data.put("users", userPairing.allUsers());
        return ResponseEntity.ok(data);
    }

    @PostMapping("/users/pair")
    public ResponseEntity<Map<String, String>> pair(@RequestBody Map<String, String> body) {
        String platform = body.get("platform");
        String userId = body.get("userId");
        if (platform == null || userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "platform and userId required"));
        }
        String code = userPairing.generatePairingCode(platform, userId);
        return ResponseEntity.ok(Map.of("code", code, "expiresIn", "300s"));
    }

    @PostMapping("/users/unpair")
    public ResponseEntity<Map<String, String>> unpair(@RequestBody Map<String, String> body) {
        String platform = body.get("platform");
        String userId = body.get("userId");
        boolean removed = userPairing.unpair(platform, userId);
        return removed
                ? ResponseEntity.ok(Map.of("status", "removed"))
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/mirror/stats")
    public ResponseEntity<Map<String, Integer>> mirrorStats() {
        return ResponseEntity.ok(sessionMirror.stats());
    }
}
