using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.RateLimiting;
using System.Threading.RateLimiting;

if (args.Length == 1 && args[0] == "--self-test")
{
    ApiOptions.SelfTest();
    OcpiTariffEvaluatorTests.Run();
    MapFilter.SelfTest();
    MapOverviewPhaseASelfTest.Run();
    MapOverviewPhaseBCompatibilitySelfTest.Run();
    MapOverviewPhaseCSelfTest.Run();
    return;
}

if (args.Length >= 1 && args[0] == "--validate-tariff-file")
{
    OcpiRealTariffValidation.Run(args);
    return;
}

var apiOptions = ApiOptions.FromEnvironment();
var builder = WebApplication.CreateBuilder(args);

builder.Logging.ClearProviders();
builder.Logging.AddSimpleConsole(options =>
{
    options.SingleLine = true;
    options.TimestampFormat = "yyyy-MM-ddTHH:mm:ssZ ";
});
builder.Logging.SetMinimumLevel(LogLevel.Warning);

builder.Services.AddSingleton(apiOptions);
builder.Services.AddSingleton<ApiDatabase>();
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext,string>(context =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = 120,
                Window = TimeSpan.FromMinutes(1),
                QueueLimit = 0,
                AutoReplenishment = true
            }));
});

var app = builder.Build();
var db = app.Services.GetRequiredService<ApiDatabase>();
await db.AssertReadOnlyRoleAsync();

app.UseForwardedHeaders(new ForwardedHeadersOptions
{
    ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto
});
app.UseHttpsRedirection();
app.UseRateLimiter();

app.MapGet("/api/v1/locations", async (
    string? q,
    string? bbox,
    double? latitude,
    double? longitude,
    int? radiusMeters,
    int? limit,
    HttpContext context,
    ApiDatabase database) =>
{
    if (!MapFilter.TryParse(context.Request.Query,out var filters,out var filterError))
        return Results.BadRequest(new { error = filterError });
    Bbox? parsed = null;
    if (!string.IsNullOrWhiteSpace(bbox))
    {
        if (!ApiOptions.TryParseBbox(bbox,out var value))
            return Results.BadRequest(new { error = "bbox must be minLon,minLat,maxLon,maxLat within valid coordinate ranges." });
        parsed = value;
    }

    try
    {
        var result = await database.SearchLocations(q,parsed,latitude,longitude,radiusMeters,ApiOptions.ParseLimit(limit),filters);
        if (filters.Active) context.Response.Headers["X-ChargeWatch-Filter-Version"] = "1";
        return Results.Ok(result);
    }
    catch (ArgumentException e)
    {
        return Results.BadRequest(new { error=e.Message });
    }
});


// Phase B opt-in preview: no client is switched over automatically.
// Enabling requires explicit deployment/rollout approval after NAS tests.
if (string.Equals(Environment.GetEnvironmentVariable("CW_ENABLE_MAP_OVERVIEW"),
    "true",StringComparison.OrdinalIgnoreCase))
{
    app.MapGet("/api/v1/map/overview/cell", async (
        int? gridLevel, long? gridX, long? gridY, int? offset, int? limit,
        HttpContext context, ApiDatabase database) =>
    {
        if (!MapFilter.TryParse(context.Request.Query,out var filters,out var filterError))
            return Results.BadRequest(new { error=filterError });
        if (gridLevel is null || gridLevel < MapGrid.MinLevel || gridLevel > MapGrid.MaxLevel ||
            gridX is null || gridY is null || gridX < 0 || gridY < 0 ||
            gridX >= (1L << gridLevel.Value) || gridY >= (1L << gridLevel.Value) ||
            offset is < 0 or > 100000 || limit is < 1 or > 100)
            return Results.BadRequest(new { error="Invalid gridLevel, gridX, gridY, offset or limit." });
        try
        {
            var result = await database.GetMapOverviewCell(gridLevel.Value,gridX.Value,gridY.Value,
                offset ?? 0,limit ?? 50,filters,context.RequestAborted);
            context.Response.Headers["X-ChargeWatch-Filter-Version"]="1";
            context.Response.Headers.CacheControl="no-store";
            return Results.Ok(result);
        }
        catch (Npgsql.PostgresException e) when (e.SqlState=="57014")
        {
            return Results.Json(new { error="Map cell query timeout.",
                code="OVERVIEW_CELL_TIMEOUT",retryable=true },statusCode:503);
        }
    });

    app.MapGet("/api/v1/map/overview", async (
        string? bbox, double? zoom, HttpContext context, ApiDatabase database) =>
    {
        if (!MapFilter.TryParse(context.Request.Query,out var filters,out var filterError))
            return Results.BadRequest(new { error=filterError });
        if (!ApiOptions.TryParseBbox(bbox,out var bounds) ||
            !double.IsFinite(bounds.MinLon) || !double.IsFinite(bounds.MinLat) ||
            !double.IsFinite(bounds.MaxLon) || !double.IsFinite(bounds.MaxLat) ||
            bounds.MinLat < -MapGrid.MaxMercatorLatitude ||
            bounds.MaxLat > MapGrid.MaxMercatorLatitude)
            return Results.BadRequest(new { error="Valid Web Mercator bbox is required." });
        if (zoom is null || !double.IsFinite(zoom.Value) || zoom < 0 || zoom > 22)
            return Results.BadRequest(new { error="Finite zoom between 0 and 22 is required." });
        try
        {
            var result = await database.GetMapOverview(bounds,zoom.Value,filters,
                context.RequestAborted);
            context.Response.Headers["X-ChargeWatch-Filter-Version"]="1";
            context.Response.Headers.CacheControl = "no-store";
            return Results.Ok(result);
        }
        catch (MapOverviewBudgetException)
        {
            return Results.Json(new { error="Map overview marker budget exceeded.",
                code="OVERVIEW_BUDGET_EXCEEDED",retryable=false },
                statusCode:503);
        }
        catch (Npgsql.PostgresException e) when (e.SqlState=="57014")
        {
            return Results.Json(new { error="Map overview database query timeout.",
                code="OVERVIEW_TIMEOUT",retryable=true },statusCode:503);
        }
    });
}

// Full-map coverage is required for an honest benchmark. On overflow ask the
// Android client to zoom in; do not return a partial map as if complete.
app.MapGet("/api/v1/map/viewport", async (string? bbox, HttpContext context, ApiDatabase database) =>
{
    if (!MapFilter.TryParse(context.Request.Query,out var filters,out var filterError))
        return Results.BadRequest(new { error = filterError });
    if (!ApiOptions.TryParseBbox(bbox, out var parsed))
        return Results.BadRequest(new { error = "Valid bbox is required." });
    if ((parsed.MaxLon-parsed.MinLon)*(parsed.MaxLat-parsed.MinLat) > 1.0)
        return Results.BadRequest(new { error = "Zoom further in." });
    var result = await database.GetMapViewport(parsed,filters);
    if (filters.Active) context.Response.Headers["X-ChargeWatch-Filter-Version"] = "1";
    return result.Truncated
        ? Results.Json(new { error = "Viewport exceeds 999 locations; zoom in." }, statusCode: 413)
        : Results.Ok(result);
});

app.MapGet("/api/v1/operators", async (ApiDatabase database) => Results.Ok(await database.GetOperators()));

app.MapGet("/api/v1/locations/{id:long}", async (long id,ApiDatabase database) =>
{
    var result = await database.GetLocation(id);
    return result is null ? Results.NotFound() : Results.Ok(result);
});

app.MapGet("/api/v1/locations/{id:long}/statistics", async (long id,ApiDatabase database) =>
{
    var result = await database.GetStatistics(id);
    return result is null ? Results.NotFound() : Results.Ok(result);
});

// External NDW GeoJSON lookup. Does not expose internal IDs as an identity requirement.
app.MapGet("/api/v1/locations/by-ndw-id/{featureId}", async (string featureId, ApiDatabase database) =>
{
    if (!NdwLocationKey.TryParse(featureId, out var key))
        return Results.BadRequest(new { error = "Invalid NDW GeoJSON feature ID." });
    var id = await database.FindLocationId(key);
    if (id is null) return Results.NotFound();
    var detail = await database.GetLocation(id.Value);
    return detail is null ? Results.NotFound() : Results.Ok(detail);
});

app.MapGet("/api/v1/locations/by-ndw-id/{featureId}/statistics", async (string featureId, ApiDatabase database) =>
{
    if (!NdwLocationKey.TryParse(featureId, out var key))
        return Results.BadRequest(new { error = "Invalid NDW GeoJSON feature ID." });
    var id = await database.FindLocationId(key);
    if (id is null) return Results.NotFound();
    var statistics = await database.GetStatistics(id.Value);
    return statistics is null ? Results.NotFound() : Results.Ok(statistics);
});

app.MapGet("/api/v1/data-status", async (ApiDatabase database)
    => Results.Ok(await database.GetDataStatus()));

await app.RunAsync();

public partial class Program { }
