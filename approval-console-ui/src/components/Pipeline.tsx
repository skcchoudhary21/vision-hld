import { Box, Typography } from '@mui/material';
import CheckIcon from '@mui/icons-material/Check';
import CloseIcon from '@mui/icons-material/Close';
import type { StageView } from '../api/types';

// Only stages actually reached (or currently in progress) are shown, in the
// order the audit trail visited them -- a branching workflow's untaken exits
// (e.g. Rejected/Cancelled/Expired on a request that's still pending) never
// appear as if they were future steps on a fixed path.
const DOT_COLORS: Record<StageView['status'], { bg: string; border: string; text: string }> = {
  COMPLETED: { bg: '#1ec488', border: '#1ec488', text: '#fff' },
  IN_PROGRESS: { bg: '#0b3d5c', border: '#0b3d5c', text: '#fff' },
  FAILED: { bg: '#dc2626', border: '#dc2626', text: '#fff' },
  PENDING: { bg: '#fff', border: '#d1d5db', text: '#9ca3af' },
};

export function Pipeline({ stages }: { stages: StageView[] }) {
  const visited = stages.filter((s) => s.status !== 'PENDING');

  return (
    <Box sx={{ display: 'flex', alignItems: 'flex-start', my: 1 }}>
      {visited.map((s, i) => {
        const colors = DOT_COLORS[s.status];
        return (
          <Box key={s.id} sx={{ display: 'flex', alignItems: 'flex-start', flex: i < visited.length - 1 ? 1 : '0 0 auto' }}>
            <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 100 }}>
              <Box sx={{
                width: 32, height: 32, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center',
                bgcolor: colors.bg, border: `2px solid ${colors.border}`, color: colors.text,
              }}>
                {s.status === 'COMPLETED' ? <CheckIcon sx={{ fontSize: 19 }} /> :
                  s.status === 'FAILED' ? <CloseIcon sx={{ fontSize: 19 }} /> :
                    <Typography sx={{ fontSize: 13, fontWeight: 700 }}>{i + 1}</Typography>}
              </Box>
              <Typography sx={{ fontSize: 12, fontWeight: 600, mt: 0.75, textAlign: 'center', color: '#444' }}>
                {s.label}
              </Typography>
              {s.requiredApprovals != null && (
                <Typography sx={{ fontSize: 11, color: '#888' }}>
                  {s.completedApprovals} / {s.requiredApprovals}
                </Typography>
              )}
            </Box>
            {i < visited.length - 1 && (
              <Box sx={{ height: 2, flex: 1, bgcolor: s.status === 'COMPLETED' ? '#1ec488' : '#d1d5db', mt: '15px' }} />
            )}
          </Box>
        );
      })}
    </Box>
  );
}
