import type { PRSearchScope } from '../../bridge/types'

export function scopeLabel(scope: PRSearchScope): string {
  switch (scope) {
    case 'currentRepo': return 'Current repo'
    case 'reviewRequested': return 'Review requested'
    case 'assigned': return 'Assigned to me'
    case 'authored': return 'Authored by me'
  }
}
