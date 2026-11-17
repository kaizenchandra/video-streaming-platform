import http from 'k6/http';
import {check} from 'k6';

export const options = {
    scenarios: {
        metadata: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 1000),
            timeUnit: '1s',
            duration: __ENV.DURATION || '30s',
            preAllocatedVUs: 100,
            maxVUs: Number(__ENV.MAX_VUS || 1000)
        }
    }, thresholds: {http_req_failed: ['rate<0.01'], http_req_duration: ['p(99)<100']}
};
export default function () {
    let r = http.get((__ENV.BASE || 'http://localhost:8090') + '/api/v1/videos', {headers: {Authorization: 'Bearer ' + __ENV.TOKEN}});
    check(r, {'200': x => x.status === 200});
}
