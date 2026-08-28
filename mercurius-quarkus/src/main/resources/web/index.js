// Mercurius application entry point.
// Manual index: importing index.scss here is what makes the bundler emit app-*.css
import 'htmx.org';
import Alpine from 'alpinejs';
import { Chart, registerables } from 'chart.js';
import './print.js';
import './index.scss';

window.Alpine = Alpine;
Alpine.start();

// T29: chart.js is a provided-scope org.mvnpm dependency resolvable ONLY at
// build time inside bundle entries, so Qute pages cannot import it directly.
// Exposing it on window lets inline <script type="module"> blocks on pages
// (templates/pages/dashboard/index.html) initialize charts from the bundle.
Chart.register(...registerables);
window.Chart = Chart;
