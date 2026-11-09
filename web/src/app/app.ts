import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Portada } from './portada/portada';

@Component({
  selector: 'app-root',
  imports: [Portada],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<triage-portada />',
})
export class App {}
