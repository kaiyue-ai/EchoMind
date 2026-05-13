# EchoMind harness command entry.
# Run from docs/harness:
#   make check
#   make test
#   make deploy

ROOT := ../..
WEB := $(ROOT)/echomind-web

.PHONY: help setup test lint check backend-build frontend-build package deploy status logs health clean

help:
	@echo "Targets:"
	@echo "  setup           Install frontend dependencies"
	@echo "  test            Run backend tests and frontend build"
	@echo "  lint            Run available lightweight checks"
	@echo "  check           Compile backend and build frontend"
	@echo "  backend-build   Compile backend without running tests"
	@echo "  frontend-build  Build Vue frontend"
	@echo "  package         Package backend runtime artifacts"
	@echo "  deploy          Build runtime images and restart backend/frontend"
	@echo "  status          Show Docker Compose service status"
	@echo "  logs            Tail backend/frontend logs"
	@echo "  health          Call backend health endpoint"

setup:
	cd $(WEB) && npm.cmd install

backend-build:
	cd $(ROOT) && mvn.cmd -q -DskipTests compile

frontend-build:
	cd $(WEB) && npm.cmd run build

test:
	cd $(ROOT) && mvn.cmd -q test
	cd $(WEB) && npm.cmd run build

lint:
	cd $(ROOT) && mvn.cmd -q -DskipTests compile
	cd $(WEB) && npm.cmd run build

check: backend-build frontend-build

package:
	cd $(ROOT) && mvn.cmd -q clean package "-Dmaven.test.skip=true"

deploy:
	cd $(ROOT) && powershell -NoProfile -Command "$$vars = @('DEEPSEEK_API_KEY','DEEPSEEK_BASE_URL','ALIYUN_BAILIAN_API_KEY','ALIYUN_BAILIAN_BASE_URL','AccessKeyID','AccessKeySecret','ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET'); foreach ($$name in $$vars) { $$value = [Environment]::GetEnvironmentVariable($$name, 'Machine'); if ($$value) { Set-Item -Path \"Env:$$name\" -Value $$value } }; mvn.cmd -q clean package '-Dmaven.test.skip=true'; docker build -f Dockerfile.runtime -t ai-agent-backend:latest .; docker build -t ai-agent-frontend:latest .\echomind-web; docker compose up -d backend frontend"

status:
	cd $(ROOT) && docker compose ps

logs:
	cd $(ROOT) && docker compose logs --tail=160 backend frontend

health:
	powershell -NoProfile -Command "Invoke-RestMethod http://localhost:8080/actuator/health | ConvertTo-Json -Depth 8"

clean:
	cd $(ROOT) && mvn.cmd -q clean
