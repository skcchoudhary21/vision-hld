import { createTheme } from '@mui/material/styles';

// Sampled from the Vision Bank logo (src/assets/vision-bank-logo.jpeg):
// navy wordmark ~#04385c, teal-green icon gradient ~#1ec488.
export const theme = createTheme({
  palette: {
    primary: { main: '#0b3d5c', dark: '#04283f', light: '#3a627d', contrastText: '#ffffff' },
    secondary: { main: '#1ec488', dark: '#149467', light: '#5cd6a8', contrastText: '#ffffff' },
    background: { default: '#f4f6f8', paper: '#ffffff' },
    success: { main: '#1ec488' },
  },
  shape: { borderRadius: 8 },
  typography: {
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    h1: { fontSize: '1.5rem', fontWeight: 700 },
    h2: { fontSize: '1.25rem', fontWeight: 700 },
  },
  components: {
    MuiButton: { styleOverrides: { root: { textTransform: 'none', fontWeight: 600 } } },
    MuiAppBar: { styleOverrides: { root: { backgroundColor: '#0b3d5c' } } },
    MuiTableCell: { styleOverrides: { head: { fontSize: '0.7rem', textTransform: 'uppercase', color: '#667', fontWeight: 700 } } },
  },
});
