import { createCollectorServer } from './server.mjs';
import { DanawaProvider } from './danawa.mjs';
import { MusinsaProvider } from './musinsa.mjs';
import { UsedProvider } from './used.mjs';

const environment = process.env.APP_ENV ?? 'development';
const provider = process.env.DATA_PROVIDER ?? 'reef-danawa';
if (!['development', 'production'].includes(environment) || !['fixture', 'reef-danawa'].includes(provider)
    || environment === 'production' && provider !== 'reef-danawa')
  throw new Error('Production requires the live reef-danawa provider');
const live = process.env.REEF_DANAWA_ENABLED === 'true';
const musinsaLive = process.env.REEF_MUSINSA_ENABLED === 'true';
const usedLive = process.env.REEF_USED_ENABLED === 'true';
createCollectorServer({ key: process.env.INTERNAL_API_KEY, fixtureEnabled: process.env.DATA_PROVIDER === 'fixture',
  danawa: live ? new DanawaProvider({ key: process.env.REEF_API_KEY }) : null,
  musinsa: musinsaLive ? new MusinsaProvider({ key: process.env.REEF_API_KEY }) : null,
  used: usedLive ? new UsedProvider({ key: process.env.REEF_API_KEY }) : null }).listen(4001, '127.0.0.1', () => {
  console.log(`Local collector: http://127.0.0.1:4001 (Danawa live ${live ? 'enabled' : 'disabled'})`);
  console.log(`Musinsa fashion live ${musinsaLive ? 'enabled' : 'disabled'}`);
  console.log(`Used listings live ${usedLive ? 'enabled' : 'disabled'}`);
});
