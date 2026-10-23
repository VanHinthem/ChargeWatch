FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY src/ChargeWatch.Importer/ChargeWatch.Importer.csproj src/ChargeWatch.Importer/
RUN dotnet restore src/ChargeWatch.Importer/ChargeWatch.Importer.csproj
COPY src/ChargeWatch.Importer/ src/ChargeWatch.Importer/
RUN dotnet publish src/ChargeWatch.Importer/ChargeWatch.Importer.csproj \
    -c Release -o /out --no-restore /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/runtime:10.0
WORKDIR /app
COPY --from=build /out/ ./
RUN mkdir -p /data/cache && chown -R $APP_UID:$APP_UID /data
USER $APP_UID
ENTRYPOINT ["dotnet","ChargeWatch.Importer.dll"]
