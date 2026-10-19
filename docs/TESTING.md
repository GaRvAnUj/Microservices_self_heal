# TESTING: Verification and Test Plan

## 1. Automated Verification Commands
### Maven Build & Unit Tests
```powershell
mvn clean test
```
*Expected*: All common-lib, service-app, and healer tests pass (0 failures, 0 errors).

### Packaging
```powershell
mvn clean package -DskipTests=false
```
*Expected*: Produces `service-app/target/service-app.jar` and `healer/target/healer.jar`.

## 2. Process Health & Workload Test (Post Step 3)
```powershell
curl -s http://localhost:8081/work
```
*Expected*: JSON response with `status: "OK"` and downstream aggregation data from `compose-post` and leaf services.

## 3. Healer Endpoints & Dashboard Test
```powershell
curl -s http://localhost:8080/actuator/health
```
*Expected*: `{"status":"UP"}`.

## 4. Manual Customer Journey (Dashboard Verification)
1. Open browser to `http://localhost:8080`.
2. Verify topology renders 6 nodes with green status badges and edges linking dependencies.
3. Switch mode to "Proposed".
4. Trigger Link Delay on `compose-post -> text` via Chaos dropdown.
5. Confirm incident opens on timeline, link is diagnosed (N1), policy is tuned via PUT (N2), and SLO recovers without restarting `compose-post`.
