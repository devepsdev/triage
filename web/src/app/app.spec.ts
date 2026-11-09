import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('App', () => {
  it('arranca', () => {
    expect(TestBed.createComponent(App).componentInstance).toBeTruthy();
  });
});
