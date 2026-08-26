import { AppBar, Toolbar, Box, Button, Select, MenuItem, Typography, type SelectChangeEvent } from '@mui/material';
import { Link, useLocation } from 'react-router-dom';
import { useActor } from '../state/ActorContext';
import { isMaker } from '../state/actors';
import logo from '../assets/vision-bank-logo.jpeg';

const MAKER_NAV = [{ to: '/my-account', label: 'My Account' }];

const CHECKER_NAV = [
  { to: '/workspace', label: 'Approval Workspace' },
  { to: '/identity', label: 'Identity & Roles' },
  { to: '/configuration', label: 'Configuration' },
];

function NavButton({ to, label, active }: { to: string; label: string; active: boolean }) {
  return (
    <Button
      component={Link}
      to={to}
      sx={{
        color: active ? '#fff' : 'rgba(255,255,255,0.65)',
        borderBottom: active ? '2px solid #1ec488' : '2px solid transparent',
        borderRadius: 0,
        px: 1.5,
        py: 1.25,
      }}
    >
      {label}
    </Button>
  );
}

export function TopNav() {
  const { actor, actors, setActorId } = useActor();
  const location = useLocation();
  const isActive = (to: string) => location.pathname.startsWith(to);
  const navItems = isMaker(actor.role) ? MAKER_NAV : CHECKER_NAV;

  return (
    <AppBar position="static" elevation={0}>
      <Toolbar sx={{ gap: 3, minHeight: 56 }}>
        <Box sx={{ bgcolor: '#fff', borderRadius: 1, px: 1, py: 0.5, display: 'flex', alignItems: 'center' }}>
          <Box component="img" src={logo} alt="Vision Bank" sx={{ height: 24, display: 'block' }} />
        </Box>
        <Box sx={{ flex: 1 }} />
        <Typography variant="body2" sx={{ color: 'rgba(255,255,255,0.7)' }}>
          {actor.name} &middot; {actor.role}
        </Typography>
        <Select
          size="small"
          value={actor.id}
          onChange={(e: SelectChangeEvent) => setActorId(e.target.value)}
          sx={{
            color: '#fff', bgcolor: 'rgba(255,255,255,0.08)', minWidth: 200,
            '& .MuiOutlinedInput-notchedOutline': { borderColor: 'rgba(255,255,255,0.3)' },
            '& .MuiSvgIcon-root': { color: '#fff' },
          }}
        >
          {actors.map((a) => (
            <MenuItem key={a.id} value={a.id}>{a.name} &middot; {a.role}</MenuItem>
          ))}
        </Select>
      </Toolbar>
      <Toolbar variant="dense" sx={{ bgcolor: '#04283f', minHeight: 44, gap: 1 }}>
        {navItems.map((item) => (
          <NavButton key={item.to} to={item.to} label={item.label} active={isActive(item.to)} />
        ))}
      </Toolbar>
    </AppBar>
  );
}
