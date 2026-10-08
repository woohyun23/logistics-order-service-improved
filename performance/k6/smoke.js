import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    http_req_failed: ['rate==0'],
    http_req_duration: ['p(95)<1000'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:19090';

export default function () {
  const health = http.get(`${baseUrl}/actuator/health`);
  check(health, {
    'health endpoint returns 200': (response) => response.status === 200,
    'application is UP': (response) => response.json('status') === 'UP',
  });

  const metrics = http.get(`${baseUrl}/actuator/prometheus`);
  check(metrics, {
    'prometheus endpoint returns 200': (response) => response.status === 200,
    'JVM metrics are exposed': (response) => response.body.includes('jvm_memory_used_bytes'),
  });

  sleep(1);
}
