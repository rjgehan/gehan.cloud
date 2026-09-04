package com.example.spring_docker_test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Client for the Meal Planner's integration API (see its INTEGRATION.md). The API key is an
 * operator credential that can read every household on that server, so it stays on this side -
 * the browser only ever talks to our own /api/kitchen endpoints.
 *
 * Reads cover the plan and the grocery list; the grocery list is the only thing writable, which
 * is a property of that API, not a restriction added here.
 *
 * Every call degrades to null when the planner isn't configured or is unreachable, matching
 * HomeAssistantService, so the dashboard keeps working without it.
 */
@Service
public class MealPlannerService {

    private static final Logger log = LoggerFactory.getLogger(MealPlannerService.class);

    private final RestClient client;
    private final boolean configured;
    private final String baseUrl;
    private final String configuredHouseholdId;

    // Resolved once from /households when not pinned by config; the id never changes at runtime.
    private volatile String cachedHouseholdId;

    public MealPlannerService(
            @Value("${app.meals.url:}") String baseUrl,
            @Value("${app.meals.api-key:}") String apiKey,
            @Value("${app.meals.household-id:}") String householdId) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.configuredHouseholdId = householdId.trim();
        this.configured = !this.baseUrl.isBlank() && !apiKey.isBlank();
        if (!configured) {
            this.client = null;
            return;
        }
        // PATCH (ticking a grocery item off) is unsupported by HttpURLConnection, which is what
        // SimpleClientHttpRequestFactory uses - hence the JDK HttpClient factory here. Redirects
        // are followed because the obvious MEALS_URL typo is http:// for an https host, and an
        // unfollowed 301 surfaces as an empty body rather than anything that hints at the cause.
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder()
                .baseUrl(this.baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-API-Key", apiKey)
                .build();
    }

    public boolean isConfigured() {
        return configured;
    }

    /** Null when the planner is off or unreachable; empty list only when it genuinely has none. */
    public List<Day> plan(LocalDate start, int days) {
        String household = householdId();
        if (household == null) {
            return null;
        }
        try {
            List<Day> plan = client.get()
                    .uri(uri -> uri.path("/api/integration/households/{id}/plan")
                            .queryParam("start", start.toString())
                            .queryParam("days", days)
                            .build(household))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Day>>() {});
            return plan == null ? null : plan.stream().map(this::absolutise).toList();
        } catch (Exception e) {
            log.warn("Failed to fetch meal plan: {}", e.toString());
            return null;
        }
    }

    public List<GroceryItem> grocery() {
        String household = householdId();
        if (household == null) {
            return null;
        }
        try {
            return client.get()
                    .uri("/api/integration/households/{id}/grocery-list", household)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GroceryItem>>() {});
        } catch (Exception e) {
            log.warn("Failed to fetch grocery list: {}", e.toString());
            return null;
        }
    }

    public GroceryItem addGrocery(String name, String quantity, String unit) {
        String household = householdId();
        if (household == null) {
            return null;
        }
        // The planner rejects a blank name with 400; quantity and unit are genuinely optional.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        if (quantity != null && !quantity.isBlank()) {
            body.put("quantity", quantity);
        }
        if (unit != null && !unit.isBlank()) {
            body.put("unit", unit);
        }
        try {
            return client.post()
                    .uri("/api/integration/households/{id}/grocery-list", household)
                    .body(body)
                    .retrieve()
                    .body(GroceryItem.class);
        } catch (Exception e) {
            log.warn("Failed to add grocery item: {}", e.toString());
            return null;
        }
    }

    public GroceryItem setGroceryChecked(String itemId, boolean checked) {
        String household = householdId();
        if (household == null) {
            return null;
        }
        try {
            return client.patch()
                    .uri("/api/integration/households/{id}/grocery-list/{item}", household, itemId)
                    .body(Map.of("checked", checked))
                    .retrieve()
                    .body(GroceryItem.class);
        } catch (Exception e) {
            log.warn("Failed to update grocery item {}: {}", itemId, e.toString());
            return null;
        }
    }

    public boolean deleteGrocery(String itemId) {
        String household = householdId();
        if (household == null) {
            return false;
        }
        try {
            client.delete()
                    .uri("/api/integration/households/{id}/grocery-list/{item}", household, itemId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete grocery item {}: {}", itemId, e.toString());
            return false;
        }
    }

    public List<RecipeSummary> recipes(String query) {
        String household = householdId();
        if (household == null) {
            return null;
        }
        try {
            List<RecipeSummary> found = client.get()
                    .uri(uri -> {
                        uri.path("/api/integration/households/{id}/recipes");
                        if (query != null && !query.isBlank()) {
                            uri.queryParam("q", query.trim());
                        }
                        return uri.build(household);
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<RecipeSummary>>() {});
            return found == null ? null : found.stream()
                    .map(r -> new RecipeSummary(r.id(), r.name(), r.description(), r.section(),
                            r.categories(), r.servings(), r.prepTimeMinutes(), r.cookTimeMinutes(),
                            r.totalTimeMinutes(), absolutiseImage(r.imageUrl())))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to fetch recipes: {}", e.toString());
            return null;
        }
    }

    public RecipeDetail recipe(String recipeId) {
        String household = householdId();
        if (household == null) {
            return null;
        }
        try {
            // householdId fills in how *this* household filed the recipe (section, categories).
            RecipeDetail r = client.get()
                    .uri(uri -> uri.path("/api/integration/recipes/{recipeId}")
                            .queryParam("householdId", household)
                            .build(recipeId))
                    .retrieve()
                    .body(RecipeDetail.class);
            if (r == null) {
                return null;
            }
            List<String> photos = r.photoUrls() == null ? List.of()
                    : r.photoUrls().stream().map(this::absolutiseImage).toList();
            return new RecipeDetail(r.id(), r.name(), r.description(), r.section(), r.categories(),
                    r.servings(), r.prepTimeMinutes(), r.cookTimeMinutes(), r.totalTimeMinutes(),
                    absolutiseImage(r.imageUrl()), photos, r.sourceUrl(), r.videoUrl(),
                    r.ingredients() == null ? List.of() : r.ingredients(),
                    r.steps() == null ? List.of() : r.steps());
        } catch (Exception e) {
            log.warn("Failed to fetch recipe {}: {}", recipeId, e.toString());
            return null;
        }
    }

    private String householdId() {
        if (!configured) {
            return null;
        }
        if (!configuredHouseholdId.isBlank()) {
            return configuredHouseholdId;
        }
        String cached = cachedHouseholdId;
        if (cached != null) {
            return cached;
        }
        try {
            List<Household> households = client.get()
                    .uri("/api/integration/households")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Household>>() {});
            if (households == null || households.isEmpty()) {
                log.warn("Meal planner returned no households");
                return null;
            }
            // One household is the normal case; app.meals.household-id pins it if that changes.
            cachedHouseholdId = households.get(0).id();
            return cachedHouseholdId;
        } catch (Exception e) {
            log.warn("Failed to resolve meal planner household: {}", e.toString());
            return null;
        }
    }

    /**
     * Image paths come back relative ("/api/images/{uuid}") because the planner doesn't know which
     * hostname you reached it on. The browser needs an absolute one, and these need no API key.
     */
    private Day absolutise(Day day) {
        return new Day(day.date(), day.meals() == null ? List.of() : day.meals().stream()
                .map(meal -> new Meal(meal.mealType(), meal.items() == null ? List.of() : meal.items().stream()
                        .map(this::absolutiseItem)
                        .toList()))
                .toList());
    }

    private Item absolutiseItem(Item item) {
        if (item.imageUrl() == null || !item.imageUrl().startsWith("/")) {
            return item;
        }
        return new Item(item.kind(), item.name(), item.servings(), item.notes(), item.recipeId(),
                absolutiseImage(item.imageUrl()), item.totalTimeMinutes(), item.placeId(),
                item.menuUrl(), item.phone());
    }

    private String absolutiseImage(String path) {
        return path == null || !path.startsWith("/") ? path : baseUrl + path;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Household(String id, String name, Integer defaultServings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Day(String date, List<Meal> meals) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meal(String mealType, List<Item> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String kind, String name, Integer servings, String notes, String recipeId,
            String imageUrl, Integer totalTimeMinutes, String placeId, String menuUrl, String phone) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroceryItem(String id, String name, String quantity, String unit, boolean checked) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipeSummary(
            String id, String name, String description, String section, List<String> categories,
            Integer servings, Integer prepTimeMinutes, Integer cookTimeMinutes,
            Integer totalTimeMinutes, String imageUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipeDetail(
            String id, String name, String description, String section, List<String> categories,
            Integer servings, Integer prepTimeMinutes, Integer cookTimeMinutes,
            Integer totalTimeMinutes, String imageUrl, List<String> photoUrls, String sourceUrl,
            String videoUrl, List<Ingredient> ingredients, List<String> steps) {
    }

    /** `text` is the whole line pre-rendered by the planner; the parts are there for columns. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ingredient(String name, String quantity, String unit, String notes, String text) {
    }
}
