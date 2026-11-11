import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
const started = new Counter('requests_started');
const completed = new Counter('requests_completed');
const invalid = new Counter('invalid_responses');
const expected = new Counter('expected_status');
const unexpected = new Counter('unexpected_status');
export let options = { scenarios: { diagnostic: { executor: 'constant-vus', vus: 200, duration: '10s', gracefulStop: '10s' } } };
export default function () {
  const id=__ENV.PRODUCT || '1';
  started.add(1);
  const response=http.get('http://host.docker.internal:5000/product/'+id+'/similar');
  completed.add(1);
  let valid=true;
  if(response.status===200) {
    try {
      const products=response.json();
      const ids=id==='1'?['2','3','4']:['3','100','1000'];
      const names=id==='1'?['Dress','Blazer','Boots']:['Blazer','Trousers','Coat'];
      const prices=id==='1'?[19.99,29.99,39.99]:[29.99,49.99,89.99];
      const available=id==='1'?[true,false,true]:[false,false,true];
      valid=Array.isArray(products) && products.length===3 && products.every((p,i)=>
        Object.keys(p).sort().join(',')==='availability,id,name,price' &&
        p.id===ids[i] && p.name===names[i] && p.price===prices[i] && p.availability===available[i] &&
        typeof p.id==='string' && typeof p.name==='string' && typeof p.price==='number' && typeof p.availability==='boolean');
    } catch (_) { valid=false; }
  } else { valid=response.body===''; }
  invalid.add(valid?0:1);
  expected.add(response.status===200?1:0);
  unexpected.add(response.status===200?0:1);
  sleep(0.5);
}
