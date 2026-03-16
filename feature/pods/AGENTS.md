# Pods Feature — Additional Agent Instructions

## Pod-Specific Rules
- Log streaming uses OkHttp WebSocket — see core/network/LogStreamRepository
- Exec sessions use SPDY-over-WebSocket — use the official k8s-client exec API
- Port-forward uses LocalPortForward from the k8s client — wrap in a service
- Multi-container pods: always show container selector before opening logs/exec
- Always stream logs line-by-line into a Flow<LogLine> — never buffer entire log

## Test command for this feature
./gradlew :feature:pods:test :feature:pods:connectedAndroidTest
