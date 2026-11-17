import http from 'k6/http';
import {check} from 'k6';

export const options = {vus: Number(__ENV.VIEWERS || 1000), duration: __ENV.DURATION || '30s'};
export default function () {
    let r = http.get(__ENV.SEGMENT_URL, {headers: {Cookie: __ENV.PLAYBACK_COOKIE}});
    check(r, {'media delivered': r => r.status === 200 && r.body.length > 0});
}
// This saturates segment delivery. It does not emulate a player's buffer or measure QoE.
