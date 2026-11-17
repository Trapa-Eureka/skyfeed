import type { Core } from '@strapi/strapi';

/** What anyone may do without signing in: read the two content types, nothing else. */
const PUBLIC_READ = [
  'api::notice.notice.find',
  'api::notice.notice.findOne',
  'api::sensor-site.sensor-site.find',
  'api::sensor-site.sensor-site.findOne',
];

/** Example content, added once to an empty CMS so the console has something to show. The sites are made up. */
const SITES = [
  { sensorId: 'gs-manila', name: 'Manila ground station', location: 'Pasay, Metro Manila', notes: 'Roof of the equipment building. Antenna shared with the weather station.' },
  { sensorId: 'gs-iloilo', name: 'Iloilo ground station', location: 'Cabatuan, Iloilo', notes: 'Unattended site. Access through the airport fire station.' },
  { sensorId: 'gs-cebu', name: 'Cebu ground station', location: 'Lapu-Lapu City, Cebu', notes: 'Receiver replaced in the last maintenance round.' },
  { sensorId: 'gs-kalibo', name: 'Kalibo ground station', location: 'Kalibo, Aklan', notes: 'Runs on the backup power line during typhoon season.' },
  { sensorId: 'gs-legazpi', name: 'Legazpi ground station', location: 'Daraga, Albay', notes: 'Signal is weaker to the north-west because of the terrain.' },
];
const NOTICES = [
  { title: 'Demo system', body: 'Sensor readings are simulated and aircraft come from a recording. Not for operational use.', severity: 'info' },
];

export default {
  register() {},

  async bootstrap({ strapi }: { strapi: Core.Strapi }) {
    const publicRole = await strapi.query('plugin::users-permissions.role').findOne({ where: { type: 'public' } });
    if (publicRole) {
      for (const action of PUBLIC_READ) {
        const where = { action, role: publicRole.id };
        if (!(await strapi.query('plugin::users-permissions.permission').findOne({ where }))) {
          await strapi.query('plugin::users-permissions.permission').create({ data: where });
        }
      }
    }

    if ((await strapi.documents('api::sensor-site.sensor-site').count({})) === 0) {
      for (const data of SITES) await strapi.documents('api::sensor-site.sensor-site').create({ data });
      strapi.log.info(`Added ${SITES.length} example sensor sites`);
    }
    if ((await strapi.documents('api::notice.notice').count({})) === 0) {
      for (const data of NOTICES) await strapi.documents('api::notice.notice').create({ data: data as never });
      strapi.log.info(`Added ${NOTICES.length} example notice`);
    }
  },
};
