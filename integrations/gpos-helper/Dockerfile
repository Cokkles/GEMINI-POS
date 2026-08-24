FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src
COPY src/Gpos.Helper/Gpos.Helper.csproj src/Gpos.Helper/
RUN dotnet restore src/Gpos.Helper/Gpos.Helper.csproj
COPY src/Gpos.Helper/ src/Gpos.Helper/
RUN dotnet publish src/Gpos.Helper/Gpos.Helper.csproj -c Release -o /app --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:8.0
WORKDIR /app
COPY --from=build /app .
ENV GPOS_Helper__ListenAddress=0.0.0.0
EXPOSE 47831
ENTRYPOINT ["dotnet", "gpos-helper.dll"]

