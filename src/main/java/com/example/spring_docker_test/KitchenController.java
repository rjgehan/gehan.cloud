package com.example.spring_docker_test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's own face on the Meal Planner. The browser talks to these paths, never to the
 * planner directly, so the operator API key stays server-side.
 *
 * 503 means the planner isn't configured (no MEALS_URL/MEALS_API_KEY); 502 means it's configured
 * but didn't answer. The kitchen page tells those apart so a blank panel is never a mystery.
 */
@RestController
@RequestMapping("/api/kitchen")
public class KitchenController {

    private static final int PLAN_DAYS = 7;

    /** The house's clock, matching WeatherService. Neither container sets TZ, so both run UTC. */
    private static final ZoneId LOCAL_ZONE = ZoneId.of("America/New_York");

    private final MealPlannerService meals;

    public KitchenController(MealPlannerService meals) {
        this.meals = meals;
    }

    @GetMapping("/plan")
    public ResponseEntity<List<MealPlannerService.Day>> plan(@RequestParam(required = false) String start) {
        // Same trap: LocalDate.now() here is UTC in the container, so after 8pm the week grid
        // started on tomorrow and the browser's "today" highlight matched no row in it.
        LocalDate from = start == null || start.isBlank() ? LocalDate.now(LOCAL_ZONE) : LocalDate.parse(start);
        return respond(meals.plan(from, PLAN_DAYS));
    }

    /**
     * Just tonight, for the Home page's dinner tile.
     *
     * Deliberately not the planner's own /today: that calls LocalDate.now() with no zone, and its
     * container runs UTC, so from 8pm local it answers with tomorrow. Sending the date explicitly
     * means the answer depends on this house's clock rather than on either server's timezone.
     */
    @GetMapping("/today")
    public ResponseEntity<MealPlannerService.Day> today() {
        List<MealPlannerService.Day> days = meals.plan(LocalDate.now(LOCAL_ZONE), 1);
        return respond(days == null || days.isEmpty() ? null : days.get(0));
    }

    @GetMapping("/grocery")
    public ResponseEntity<List<MealPlannerService.GroceryItem>> grocery() {
        return respond(meals.grocery());
    }

    @PostMapping("/grocery")
    public ResponseEntity<MealPlannerService.GroceryItem> addGrocery(@RequestBody NewItem req) {
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return respond(meals.addGrocery(req.name().trim(), req.quantity(), req.unit()));
    }

    @PatchMapping("/grocery/{itemId}")
    public ResponseEntity<MealPlannerService.GroceryItem> checkGrocery(
            @PathVariable String itemId, @RequestBody CheckRequest req) {
        return respond(meals.setGroceryChecked(itemId, req.checked()));
    }

    @DeleteMapping("/grocery/{itemId}")
    public ResponseEntity<Void> deleteGrocery(@PathVariable String itemId) {
        if (!meals.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return meals.deleteGrocery(itemId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    @GetMapping("/recipes")
    public ResponseEntity<List<MealPlannerService.RecipeSummary>> recipes(
            @RequestParam(required = false) String q) {
        return respond(meals.recipes(q));
    }

    @GetMapping("/recipes/{recipeId}")
    public ResponseEntity<MealPlannerService.RecipeDetail> recipe(@PathVariable String recipeId) {
        return respond(meals.recipe(recipeId));
    }

    private <T> ResponseEntity<T> respond(T body) {
        if (!meals.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return body == null
                ? ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
                : ResponseEntity.ok(body);
    }

    public record NewItem(String name, String quantity, String unit) {
    }

    public record CheckRequest(boolean checked) {
    }
}
