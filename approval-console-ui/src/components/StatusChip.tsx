import { Chip } from '@mui/material';

const COLOR_MAP: Record<string, { bg: string; color: string }> = {
  PENDING: { bg: '#fef3c7', color: '#92400e' },
  PENDING_APPROVAL: { bg: '#fef3c7', color: '#92400e' },
  RELEASE_PENDING: { bg: '#fef3c7', color: '#92400e' },
  APPROVED: { bg: '#dcfce7', color: '#166534' },
  RELEASED: { bg: '#dcfce7', color: '#166534' },
  COMPLETED: { bg: '#dcfce7', color: '#166534' },
  REJECTED: { bg: '#fee2e2', color: '#991b1b' },
  CANCELLED: { bg: '#f3f4f6', color: '#6b7280' },
  EXPIRED: { bg: '#f3f4f6', color: '#6b7280' },
};

export function StatusChip({ label }: { label: string }) {
  const colors = COLOR_MAP[label.toUpperCase()] ?? { bg: '#e5e7eb', color: '#374151' };
  return (
    <Chip
      label={label}
      size="small"
      sx={{ bgcolor: colors.bg, color: colors.color, fontWeight: 700, fontSize: 11 }}
    />
  );
}
