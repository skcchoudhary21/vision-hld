import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { ACTOR_STORAGE_KEY, ACTORS, type Actor } from './actors';

interface ActorContextValue {
  actor: Actor;
  setActorId: (id: string) => void;
  actors: Actor[];
}

const ActorContext = createContext<ActorContextValue | null>(null);

export function ActorProvider({ children }: { children: ReactNode }) {
  const [actorId, setActorId] = useState<string>(
    () => localStorage.getItem(ACTOR_STORAGE_KEY) ?? ACTORS[0].id,
  );

  const value = useMemo<ActorContextValue>(() => ({
    actor: ACTORS.find((a) => a.id === actorId) ?? ACTORS[0],
    actors: ACTORS,
    setActorId: (id: string) => {
      localStorage.setItem(ACTOR_STORAGE_KEY, id);
      setActorId(id);
    },
  }), [actorId]);

  return <ActorContext.Provider value={value}>{children}</ActorContext.Provider>;
}

export function useActor(): ActorContextValue {
  const ctx = useContext(ActorContext);
  if (!ctx) throw new Error('useActor must be used within an ActorProvider');
  return ctx;
}
