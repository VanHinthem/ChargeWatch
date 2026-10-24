using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.RateLimiting;
using System.Threading.RateLimiting;

if (args.Length == 1 && args[0] == "--self-test")
{
    ApiOptions.SelfTest();
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
    ApiDatabase database) =>
{
    Bbox? parsed = null;
    if (!string.IsNullOrWhiteSpace(bbox))
    {
        if (!ApiOptions.TryParseBbox(bbox,out var value))
            return Results.BadRequest(new { error = "bbox must be minLon,minLat,maxLon,maxLat within valid coordinate ranges." });
        parsed = value;
    }

    try
    {
        var result = await database.SearchLocations(q,parsed,latitude,longitude,radiusMeters,ApiOptions.ParseLimit(limit));
        return Results.Ok(result);
    }
    catch (ArgumentException e)
    {
        return Results.BadRequest(new { error=e.Message });
    }
});

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
