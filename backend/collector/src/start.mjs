import { createCollectorServer } from './server.mjs';
import { DanawaProvider } from './danawa.mjs';

if ((process.env.APP_ENV ?? 'development') !== 'development' || !['fixture', 'reef-danawa'].includes(process.env.DATA_PROVIDER ?? 'reef-danawa'))
  throw new Error('Only development fixture mode is implemented');
const live = process.env.REEF_DANAWA_ENABLED === 'true';
createCollectorServer({ key: process.env.INTERNAL_API_KEY, fixtureEnabled: process.env.DATA_PROVIDER === 'fixture', danawa: live ? new DanawaProvider({ key: process.env.REEF_API_KEY }) : null }).listen(4001, '127.0.0.1', () => {
  console.log(`Local collector: http://127.0.0.1:4001 (Danawa live ${live ? 'enabled' : 'disabled'})`);
});
